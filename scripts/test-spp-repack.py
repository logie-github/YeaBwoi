"""Exercise the external SPP release natively on Windows, in disposable CI storage."""
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import tempfile
import time
import urllib.request

import gdown

def emit(name, value):
    print(name + "=" + json.dumps(value), flush=True)

def ready(process, port, log, timeout=180):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"Process exited ({process.returncode}): " + log.read_text(errors="replace")[-7000:])
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=1):
                return
        except OSError:
            time.sleep(1)
    raise RuntimeError(f"Port {port} did not open: " + log.read_text(errors="replace")[-7000:])

with tempfile.TemporaryDirectory(prefix="spp-runtime-") as work:
    root = Path(work)
    archive = root / "server.7z"
    emit("DOWNLOADING_SPP", True)
    if not gdown.download(id="1AUYiD-2NpamLFzxJDBNVRcO7FBg9YwIi", output=str(archive), quiet=True):
        raise RuntimeError("The SPP source was unavailable")
    with archive.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != "c161a7289b907ba86471374253f7b7844a6f49377722ca706da3b40f90d077e0":
            raise RuntimeError("The inspected SPP archive changed")
    seven = r"C:\Program Files\7-Zip\7z.exe"
    prefix = "SSP Legion 7.3.5 - Single Core/SingleCore_Legion/spp-legion"
    subprocess.run([seven, "x", "-y", "-bd", f"-o{root}", str(archive), prefix + "/*"], check=True, stdout=subprocess.DEVNULL)
    core = root / prefix
    archive.unlink()
    database = core / "Server/Database"
    bin_dir = core / "Server/bin64"
    realm = core / "Realms/1"
    environment = {key: value for key, value in os.environ.items()
                   if not re.search(r"(?i)token|secret|password|credential", key)}
    processes = []
    handles = []

    def launch(executable, arguments, directory, name):
        log = root / (name + ".log")
        handle = log.open("wb")
        handles.append(handle)
        process = subprocess.Popen([str(executable), *arguments], cwd=directory, env=environment,
                                   stdin=subprocess.PIPE, stdout=handle, stderr=subprocess.STDOUT,
                                   creationflags=subprocess.CREATE_NEW_PROCESS_GROUP)
        processes.append(process)
        return process, log

    def sql(query):
        result = subprocess.run([str(database / "bin/mysql.exe"),
            "--defaults-extra-file=" + str(database / "connection.cnf"), "--protocol=TCP", "--port=3312",
            "--batch", "--skip-column-names", "realmd"], input=query, text=True, capture_output=True,
            cwd=core, env=environment, timeout=60)
        if result.returncode:
            raise RuntimeError(result.stderr)
        return result.stdout.strip()

    def config(path, world=False):
        text = path.read_text(errors="replace")
        def connection(match):
            values = match[2].split(";")
            values[0:2] = ["127.0.0.1", "3312"]
            return match[1] + '"' + ";".join(values) + '"'
        text = re.sub(r'(?m)^(\s*(?:Login|World|Character|Hotfix)DatabaseInfo\s*=\s*)"([^"]+)"', connection, text)
        settings = {"BindIP": '"127.0.0.1"', "LoginREST.ExternalAddress": '"127.0.0.1"', "LoginREST.LocalAddress": '"127.0.0.1"'}
        if world:
            settings.update({"WorldServerPort": "8095", "InstanceServerPort": "8096", "Console.Enable": "1"})
        for key, value in settings.items():
            pattern = re.compile(r"(?m)^\s*" + re.escape(key) + r"\s*=.*$")
            text = pattern.sub(key + "=" + value, text) if pattern.search(text) else text + "\n" + key + "=" + value
        output = path.with_name("app-" + path.name)
        output.write_text(text)
        return output

    try:
        db, db_log = launch(database / "bin/mysqld.exe", ["--defaults-file=" + str(database / "my-large.ini"),
            "--basedir=" + str(database), "--datadir=" + str(database / "data"), "--port=3312",
            "--bind-address=127.0.0.1", "--console", "--standalone"], core, "database")
        ready(db, 3312, db_log)
        emit("DATABASE_READY", True)
        tables = sql("SHOW TABLES;").splitlines()
        emit("AUTH_TABLES", tables)
        for table in ("account", "battlenet_accounts", "account_access", "build_info", "battlenet_components", "realmlist"):
            if table in tables:
                emit("SCHEMA", {"table": table, "columns": sql("SHOW COLUMNS FROM `" + table + "`;").splitlines()})
        for executable in ("bnetserver.exe", "worldserver.exe"):
            result = subprocess.run([str(bin_dir / executable), "--help"], cwd=realm, env=environment,
                                    capture_output=True, text=True, timeout=30)
            emit("CLI", {"executable": executable, "code": result.returncode, "help": result.stdout[-4000:], "error": result.stderr[-1500:]})
            if result.returncode:
                raise RuntimeError("The repack executable cannot run: " + executable)
            version = subprocess.run([str(bin_dir / executable), "--version"], cwd=realm, env=environment,
                                     capture_output=True, text=True, timeout=30)
            emit("CORE_VERSION", {"executable": executable, "text": version.stdout, "error": version.stderr})
        if "build_info" in tables:
            builds = sql("SELECT build FROM build_info ORDER BY build;")
        elif "battlenet_components" in tables:
            emit("BATTLENET_COMPONENTS", sql("SELECT * FROM battlenet_components;").splitlines())
            builds = sql("SELECT Build FROM battlenet_components WHERE Program='WoW' ORDER BY Build;")
        else:
            # This old fixed-build SPP release has no build metadata table. Bind it to the
            # publisher's documented 26972 release by its inspected executable digests.
            pins = {"bnetserver.exe": "8bb9eea52f8f7421b75f9fd92311bea74ef3f6d001a1986dd0cfee96449675c3",
                    "worldserver.exe": "d54437a169b9bc00d56e809ace78629e78cbf2fdb7008c5feca9374df25b7cf5"}
            for executable, expected in pins.items():
                if hashlib.sha256((bin_dir / executable).read_bytes()).hexdigest() != expected:
                    raise RuntimeError("The fixed-build SPP executable changed: " + executable)
            emit("FIXED_BUILD_RELEASE", {"build": 26972, "executable_sha256": pins})
            builds = "26972"
        emit("SUPPORTED_BUILDS", builds.splitlines())
        if "26972" not in builds.splitlines():
            raise RuntimeError("The actual authentication database does not support build 26972")
        sql("UPDATE realmlist SET address='127.0.0.1',localAddress='127.0.0.1',port=8095,gamebuild=26972 WHERE id=1;")
        login_config = config(realm / "Settings/login.conf")
        world_config = config(realm / "Settings/world.conf", True)
        login, login_log = launch(bin_dir / "bnetserver.exe", ["-c", str(login_config)], realm, "authentication")
        ready(login, 1119, login_log)
        ready(login, 8081, login_log)
        emit("AUTHENTICATION_READY", True)
        with urllib.request.urlopen("http://127.0.0.1:8081/bnetserver/login/", timeout=10) as response:
            form = json.loads(response.read(16384).decode())
            emit("LOGIN_FORM", {"status": response.status, "body": form})
        email = "PROBE" + str(int(time.time())) + "@LOCAL.INVALID"
        password = "SppProbe-4829"
        email_hash = hashlib.sha256(email.encode()).hexdigest().upper()
        password_hash = hashlib.sha256((email_hash + ":" + password.upper()).encode()).digest()[::-1].hex().upper()
        sql("START TRANSACTION; INSERT INTO battlenet_accounts(email,sha_pass_hash) VALUES('" + email + "','" + password_hash + "'); "
            "SET @b=LAST_INSERT_ID(); SET @n=CONCAT(@b,'#1'); "
            "INSERT INTO account(username,sha_pass_hash,email,reg_mail,expansion,battlenet_account,battlenet_index) "
            "VALUES(@n,UPPER(SHA1(CONCAT(@n,':','" + password.upper() + "'))),'" + email + "','" + email + "',6,@b,1); "
            "SET @g=LAST_INSERT_ID(); INSERT INTO realmcharacters(realmid,acctid,numchars) SELECT id,@g,0 FROM realmlist; COMMIT;")
        for item in form.get("inputs", []):
            identifier = item.get("input_id", item.get("id"))
            if identifier == "account_name":
                item["value"] = email
            elif identifier == "password":
                item["value"] = password
        request = urllib.request.Request("http://127.0.0.1:8081/bnetserver/login/", data=json.dumps(form).encode(),
                                         headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=15) as response:
            login_result = json.loads(response.read(16384).decode())
        state = login_result.get("authentication_state", login_result.get("authenticationState"))
        ticket = login_result.get("login_ticket", login_result.get("loginTicket"))
        emit("ACCOUNT_LOGIN", {"state": state, "ticket_received": bool(ticket), "keys": list(login_result)})
        if str(state).upper() != "DONE" or not ticket:
            raise RuntimeError("The newly created account did not authenticate through SPP's real login service")
        world, world_log = launch(bin_dir / "worldserver.exe", ["-c", str(world_config)], realm, "world")
        ready(world, 8095, world_log, 1200)
        emit("WORLD_READY", True)
        world.stdin.write(b"server shutdown 1\n")
        world.stdin.flush()
        world.wait(timeout=90)
        emit("WORLD_GRACEFUL_STOP", world.returncode)
        login.terminate()
        login.wait(timeout=30)
        result = subprocess.run([str(database / "bin/mysqladmin.exe"),
            "--defaults-extra-file=" + str(database / "connection.cnf"), "--protocol=TCP", "--port=3312", "shutdown"],
            cwd=core, env=environment, capture_output=True, text=True, timeout=60)
        if result.returncode:
            raise RuntimeError(result.stderr)
        db.wait(timeout=90)
        emit("DATABASE_GRACEFUL_STOP", db.returncode)
        emit("SPP_RUNTIME_VERIFIED", True)
    finally:
        for process in reversed(processes):
            if process.poll() is None:
                process.kill()
                process.wait(timeout=30)
        for handle in handles:
            handle.close()
