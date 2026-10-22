"""Inspect external SPP acquisition sources in disposable storage; never run their programs."""
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import tempfile

import gdown

def emit(kind, value):
    print(kind + "=" + json.dumps(value), flush=True)

with tempfile.TemporaryDirectory(prefix="spp-sources-") as work:
    root = Path(work)
    for kind, source in (("client", "1tCP3mJUqsDK1H4zug4bQZOUrAzAX30ud"),
                         ("server", "1AUYiD-2NpamLFzxJDBNVRcO7FBg9YwIi")):
        archive = root / (kind + ".archive")
        emit("DOWNLOAD", {"kind": kind, "id": source})
        if not gdown.download(id=source, output=str(archive), quiet=True):
            raise RuntimeError("Google Drive did not supply " + kind)
        with archive.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        emit("ARCHIVE", {"kind": kind, "bytes": archive.stat().st_size, "sha256": digest})
        listing = subprocess.check_output(["7z", "l", "-slt", str(archive)], text=True)
        records = [dict(line.split(" = ", 1) for line in block.splitlines() if " = " in line)
                   for block in listing.split("\n\n")]
        records = [record for record in records if "Path" in record and "Size" in record]
        selected = [r["Path"] for r in records if re.search(
            r"(?i)(?:^|[/\\])(?:[^/\\]*wow[^/\\]*|mysqld[^/\\]*|mariadbd|mysql|mysqladmin|mariadb|"
            r"mariadb-admin|bnetserver|worldserver|connection_patcher)\.exe$|\.(?:ini|cnf|conf|bat|cmd|wtf)$|"
            r"(?:^|[/\\])(?:cdns|versions|\.build\.info)$", r["Path"])]
        emit("STARTUP_FILES", {"kind": kind, "files": selected})
        emit("DIRECTORY_SIZES", {"kind": kind, "bytes": sum(int(r["Size"]) for r in records), "files": len(records)})
        emit("DATABASE_ROOTS", [r["Path"] for r in records if re.search(r"(?i)(?:mysql\.ibd|ibdata1|aria_log_control)$", r["Path"])])
        emit("SCHEMAS", sorted({str(Path(r["Path"]).parent) for r in records if re.search(r"(?i)\.(ibd|frm|myd|myi|mai|mad)$", r["Path"])}))
        output = root / kind
        subprocess.run(["7z", "x", "-y", "-bd", f"-o{output}", str(archive), *selected], check=True, stdout=subprocess.DEVNULL)
        for path in sorted(output.rglob("*")):
            if not path.is_file():
                continue
            relative = str(path.relative_to(output))
            if path.suffix.lower() == ".exe":
                data = path.read_bytes()
                position = data.find(bytes.fromhex("bd04effe"))
                version = None
                if position >= 0 and len(data) >= position + 16:
                    high, low = struct.unpack_from("<II", data, position + 8)
                    version = [high >> 16, high & 0xffff, low >> 16, low & 0xffff]
                emit("EXECUTABLE", {"kind": kind, "path": relative, "bytes": len(data), "version": version, "sha256": hashlib.sha256(data).hexdigest()})
            elif path.stat().st_size < 2_000_000:
                lines = path.read_text(errors="replace").splitlines()
                if path.suffix.lower() in {".bat", ".cmd"}:
                    # Startup requirements only; never print password assignments.
                    lines = [line for line in lines if re.search(r"(?i)mysqld|bnetserver|worldserver|(?:^|\s)(?:cd|pushd)\b|\.conf|\.ini|wow.*exe", line) and not re.search(r"(?i)password|passwd|pwd=", line)]
                    emit("SCRIPT", {"path": relative, "lines": lines})
                else:
                    values = {}
                    for line in lines:
                        key, separator, value = line.partition("=")
                        if separator and re.match(r"(?i)^\s*(?:DataDir|RealmID|WorldServerPort|BattlenetPort|LoginREST\.|BindIP|port|basedir|datadir|tmpdir|bind-address|Game\.Build|Solocraft|SoloMode|AutoBalance|Bot|PlayerBot)", key):
                            values[key.strip()] = value.strip()
                        elif separator and re.fullmatch(r"(?i)\s*(?:Login|World|Character|Hotfix)DatabaseInfo\s*", key):
                            cells = value.strip().strip('"').split(";")
                            values[key.strip()] = {"fields": len(cells), "database": cells[-1], "port": cells[1] if len(cells) == 5 else None}
                    if values:
                        emit("CONFIG", {"path": relative, "values": values})
                    if path.name.lower() in {"cdns", "versions", ".build.info"}:
                        emit("CLIENT_BOOTSTRAP", {"path": relative, "lines": lines[:12]})
        archive.unlink()
    emit("SPP_SOURCE_INSPECTION_COMPLETE", True)
