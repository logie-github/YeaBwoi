"""Inspect the user's external archive in disposable CI storage; never run its programs."""
import configparser
import hashlib
import json
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
    # Extract only text configuration/startup files. All files stay in this disposable runner.
    text_paths = [path for path in selected if not path.lower().endswith(".exe")]
    subprocess.run(["7z", "x", "-y", "-bd", f"-o{output}", str(archive), *text_paths], check=True,
                   stdout=subprocess.DEVNULL)
    allowed = {"datadir", "basedir", "tmpdir", "port", "bind-address", "defaults-file", "mysql_tcp_port",
               "innodb_data_home_dir", "innodb_log_group_home_dir", "mysql_home", "mysqld_exe",
               "datadir", "realmid", "worldserverport", "battlenetport", "loginrest.port"}
    for path in output.rglob("*"):
        if not path.is_file() or path.stat().st_size > 2_000_000:
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
    print("DRACONIC_ARCHIVE_INSPECTED", flush=True)
