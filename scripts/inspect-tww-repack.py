"""Inspect the user's external archive in disposable CI storage; never run its programs."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

import gdown

with tempfile.TemporaryDirectory(prefix="draconic-inspect-") as work:
    root = Path(work)
    archive = root / "repack.archive"
    print("Downloading the supplied Draconic repack", flush=True)
    if not gdown.download(id="1CQN9_AmAL7yr7_sAfn6GDyjgHMUwwdQ0", output=str(archive), quiet=True):
        raise RuntimeError("Google Drive did not supply the repack")
    with archive.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    print(json.dumps({"bytes": archive.stat().st_size, "sha256": digest}), flush=True)
    if digest != "3e3c4a9fa60a7e65b9d5f50e13d574a923d03ef03ce7a7c78436df038471887e":
        raise RuntimeError("The supplied repack changed since its initial inspection")
    listing = subprocess.check_output(["7z", "l", "-slt", str(archive)], text=True)
    records = [dict(line.split(" = ", 1) for line in block.splitlines() if " = " in line)
               for block in listing.split("\n\n")]
    paths = [record["Path"] for record in records if "Path" in record and "Size" in record]
    selected = [path for path in paths if re.search(
        r"(?i)(?:^|[/\\])(?:mysqld[^/\\]*|mariadbd|mysql|mysqladmin|mariadb|mariadb-admin|"
        r"unicontroller|bnetserver|worldserver)\.exe$|\.(?:ini|cnf|conf|bat|cmd)$", path)]
    print("STARTUP_FILES=" + json.dumps(selected), flush=True)
    database = [path for path in paths if re.search(r"(?i)(?:mysql\.ibd|ibdata1|auto\.cnf|aria_log_control)$", path)]
    print("DATABASE_ROOTS=" + json.dumps(database), flush=True)
    print("SCHEMA_DIRECTORIES=" + json.dumps(sorted({str(Path(path).parent) for path in paths
          if re.search(r"(?i)\.(ibd|frm|myd|myi|mai|mad)$", path)})), flush=True)
    output = root / "metadata"
    # Extract configuration, binaries (never run) and one real table per required schema.
    # The app verifier must see the actual files rather than a generated installation.
    samples = []
    for schema in ("twwauth", "twwworld", "twwcharacters", "twwhotfixes"):
        tables = [record for record in records if f"/core/mysql/data/{schema}/" in record.get("Path", "")
                  and record["Path"].lower().endswith(".ibd")]
        samples.append(min(tables, key=lambda record: int(record["Size"]))["Path"])
    samples += [path for path in database if path.lower().endswith("mysql.ibd")]
    subprocess.run(["7z", "x", "-y", "-bd", f"-o{output}", str(archive), *selected, *samples], check=True,
                   stdout=subprocess.DEVNULL)
    allowed = {"datadir", "basedir", "tmpdir", "port", "bind-address", "defaults-file", "mysql_tcp_port",
               "innodb_data_home_dir", "innodb_log_group_home_dir", "mysql_home", "mysqld_exe",
               "datadir", "realmid", "worldserverport", "battlenetport", "loginrest.port"}
    for path in output.rglob("*"):
        if not path.is_file() or path.suffix.lower() not in {".conf", ".cnf", ".ini", ".bat", ".cmd"} or path.stat().st_size > 2_000_000:
            continue
        text = path.read_text(errors="replace")
        values = {}
        for line in text.splitlines():
            key, separator, value = line.partition("=")
            if separator and key.strip().lower() in allowed:
                values[key.strip()] = value.strip()
            elif separator and re.fullmatch(r"(?i)(?:Login|World|Character|Hotfix)DatabaseInfo", key.strip()):
                cells = value.strip().strip('"').split(";")
                values[key.strip()] = {"fields": len(cells), "database": cells[-1], "port": cells[1] if len(cells) == 5 else None}
        if values:
            print("CONFIG=" + json.dumps({"path": str(path.relative_to(output)), "values": values}), flush=True)
        if path.suffix.lower() in {".bat", ".cmd"}:
            relevant = [line for line in text.splitlines() if re.search(r"(?i)mysqld|bnetserver|worldserver|^\s*(?:cd|pushd)\b", line)]
            print("STARTUP=" + json.dumps({"path": str(path.relative_to(output)), "lines": relevant}), flush=True)
    test = Path("warcraft/unit-tests/src/test/kotlin/com/logie/warcraft/tww/core/TwwLiveRepackTest.kt")
    test.parent.mkdir(parents=True, exist_ok=True)
    test.write_text('''package com.logie.warcraft.tww.core
    import java.io.File
    import kotlin.test.*
    class TwwLiveRepackTest {
        @Test fun discoversActualDraconicArchiveWithoutTreatingItsBackupAsActive() {
            val root = File(System.getenv("TWW_REPACK_ROOT"))
            val report = TwwInstall.inspect(File(root, "absent-client"), root)
            val server = assertNotNull(report.detected)
            val database = assertNotNull(server.databaseProfile)
            val active = File(root, "DraconicServer/UniServerZ/core/mysql")
            assertEquals(File(active, "bin/mysqld_z.exe"), server.databaseExecutable)
            assertEquals(File(active, "my.ini"), server.databaseConfig)
            assertEquals(File(active, "data"), database.dataDirectory)
            assertEquals(File(active, "bin/mysql.exe"), database.client)
            assertEquals(File(active, "bin/mysqladmin.exe"), database.admin)
            assertEquals(3306, database.port)
            assertEquals(TwwInstall.Status.INSTALLED, report.database.status)
            assertEquals(TwwInstall.Role.entries.toSet(), server.commands.keys)
            assertEquals(listOf(TwwInstall.UNKNOWN_BUILD_ISSUE), server.issues)
            assertEquals(emptyList(), report.core.missing)
            println("LIVE_DRACONIC_VERIFIED: actual daemon, config, data, tools, port and startup commands; runtime build check retained")
        }
    }
    ''')
    subprocess.run(["gradle", "--no-daemon", "-p", "warcraft/unit-tests", "test", "--tests", "*TwwLiveRepackTest"],
                   env=os.environ | {"TWW_REPACK_ROOT": str(output)}, check=True)
    print("DRACONIC_ARCHIVE_INSPECTED", flush=True)
