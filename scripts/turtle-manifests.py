#!/usr/bin/env python3
"""Writes the checksums the World of Warcraft app checks Turtle WoW's files against, from a
Tortoise-WoW checkout (scripts/fetch-tortoise.sh) at the commit pinned in
warcraft/turtle/server-version.env:

- client-manifest.tsv: every file of the 1.18.1 (7272) client with its size and SHA-256, and the
  files the final client no longer has, from tools/dbc_verification/manifest.json (Turtle WoW's
  own launcher manifest, retrieved by the Tortoise project). The download mirrors in it are left
  out: the app never downloads the client.
- dbc-sha256.tsv: the SHA-256 of each DBC file extracted from that client, from
  tools/dbc_verification/dbc_verifier.py.

  scripts/turtle-manifests.py build/tortoise warcraft/android/app/src/main/assets/turtle
  scripts/turtle-manifests.py --check build/tortoise warcraft/android/app/src/main/assets/turtle
"""
import ast
import json
import pathlib
import sys


def client_manifest(source: pathlib.Path) -> str:
    data = json.loads((source / "tools/dbc_verification/manifest.json").read_text())
    lines = ["# kind\tpath\tsize\tsha256 (Tortoise-WoW tools/dbc_verification/manifest.json)"]
    for entry in sorted(data["client"], key=lambda e: e["name"].lower()):
        name = entry["name"]
        if "\t" in name or name.startswith("/") or ".." in name.split("/"):
            raise SystemExit(f"Unsafe path in the manifest: {name!r}")
        if entry["type"] == "file":
            lines.append(f"file\t{name}\t{int(entry['size'])}\t{entry['hash'].lower()}")
        elif entry["type"] == "del":
            lines.append(f"gone\t{name}\t-\t-")
        else:
            raise SystemExit(f"Unknown manifest entry type {entry['type']!r}")
    return "\n".join(lines) + "\n"


def dbc_hashes(source: pathlib.Path) -> str:
    tree = ast.parse((source / "tools/dbc_verification/dbc_verifier.py").read_text())
    hashes = None
    for node in ast.walk(tree):
        if isinstance(node, ast.Assign) and any(getattr(t, "id", None) == "EXPECTED_HASHES" for t in node.targets):
            hashes = ast.literal_eval(node.value)
    if not hashes:
        raise SystemExit("No EXPECTED_HASHES in dbc_verifier.py")
    lines = ["# file\tsha256 (Tortoise-WoW tools/dbc_verification/dbc_verifier.py)"]
    for name in sorted(hashes, key=str.lower):
        if "/" in name or "\t" in name:
            raise SystemExit(f"Unsafe DBC name: {name!r}")
        lines.append(f"{name}\t{hashes[name].lower()}")
    return "\n".join(lines) + "\n"


def main() -> None:
    args = sys.argv[1:]
    check = args[:1] == ["--check"]
    if check:
        args = args[1:]
    if len(args) != 2:
        raise SystemExit(__doc__)
    source, out = pathlib.Path(args[0]), pathlib.Path(args[1])
    files = {"client-manifest.tsv": client_manifest(source), "dbc-sha256.tsv": dbc_hashes(source)}
    stale = []
    for name, text in files.items():
        path = out / name
        if check:
            if not path.is_file() or path.read_text() != text:
                stale.append(name)
        else:
            out.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
            print(f"{path}: {text.count(chr(10)) - 1} entries")
    if stale:
        raise SystemExit(f"Out of date with the pinned Tortoise-WoW (run scripts/turtle-manifests.py): {', '.join(stale)}")


if __name__ == "__main__":
    main()
