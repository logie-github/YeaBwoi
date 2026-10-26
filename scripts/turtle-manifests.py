#!/usr/bin/env python3
"""Writes the checksums the World of Warcraft app checks OctoWoW's server data against, from a
Tortoise-WoW checkout (scripts/fetch-tortoise.sh) at the commit pinned in
warcraft/turtle/server-version.env:

- dbc-sha256.tsv: the SHA-256 of each DBC file extracted from Turtle WoW's final 1.18.1 (7272)
  client, which the realm is made for, from tools/dbc_verification/dbc_verifier.py. OctoWoW's
  client has its own versions of some (assets/turtle/octowow-dbc.txt). The client itself is
  checked against OctoWoW's torrent, which the app reads from OctoWoW's server.

  scripts/turtle-manifests.py build/tortoise warcraft/android/app/src/main/assets/turtle
  scripts/turtle-manifests.py --check build/tortoise warcraft/android/app/src/main/assets/turtle
"""
import ast
import pathlib
import sys


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
    files = {"dbc-sha256.tsv": dbc_hashes(source)}
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
