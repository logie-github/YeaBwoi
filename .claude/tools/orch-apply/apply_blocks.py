#!/usr/bin/env python3
"""apply_blocks: deterministic SEARCH/REPLACE applier.

The execution half of /orch-code-anth §4.9 (the v5.0 apply-tier), extracted as a
real tool so both the coding skill and /orch-clean's mechanical realignments run
the SAME applier instead of an ad-hoc per-run reimplementation.

Block format (the only sanctioned one — see the skill's "why FORMAT is
load-bearing"):

    FILE: <path from repo root>
    <<<<<<< SEARCH
    <verbatim current text>
    =======
    <replacement text>
    >>>>>>> REPLACE

Contract:
- A block applies IFF its SEARCH text is found EXACTLY ONCE in the current
  in-memory state of the file. Zero matches -> nomatch. Two or more -> nonunique.
- Non-matching blocks are NEVER force-applied. They are reported as residue for
  escalation (re-emit with more context, or hand one block to a cheap worker).
- Blocks for one file apply sequentially against the evolving in-memory text, so
  a later block sees earlier blocks' results. The file is written once, at the end.
- Line endings are preserved. An exact byte match is tried first; if that fails,
  an EOL-normalized match is tried and counted separately (`eol` kind) so the
  fidelity cost of CRLF/LF drift stays measurable rather than hidden.

stdlib only. Python 3.12. Windows-first.

Usage:
    python apply_blocks.py plan.blocks --root . --json report.json
    python apply_blocks.py plan.blocks --dry-run
    python apply_blocks.py --atomic plan.blocks     # refuse partial application
"""
from __future__ import annotations

import argparse
import dataclasses
import json
import os
import sys

SEARCH_OPEN = "<<<<<<< SEARCH"
DIVIDER = "======="
REPLACE_CLOSE = ">>>>>>> REPLACE"
FILE_PREFIX = "FILE:"


class BlockParseError(ValueError):
    """A malformed block. Parsing is strict: a bad block is worse than no block."""


@dataclasses.dataclass
class Block:
    path: str
    search: str
    replace: str
    index: int
    line: int


@dataclasses.dataclass
class BlockResult:
    path: str
    index: int
    status: str  # applied | nomatch | nonunique | error
    kind: str = "exact"  # exact | eol
    detail: str = ""


def parse_blocks(text: str) -> list[Block]:
    """Parse SEARCH/REPLACE blocks. Raises BlockParseError on malformed input.

    Strictness is deliberate: silently skipping a block the orchestrator believed
    it emitted would produce a partial migration that the counts call clean.
    """
    lines = text.split("\n")
    blocks: list[Block] = []
    i = 0
    pending_path: str | None = None
    pending_path_line = 0

    while i < len(lines):
        raw = lines[i]
        stripped = raw.strip()

        if stripped.startswith(FILE_PREFIX):
            pending_path = stripped[len(FILE_PREFIX):].strip()
            pending_path_line = i + 1
            i += 1
            continue

        if stripped == SEARCH_OPEN:
            open_line = i + 1
            if not pending_path:
                raise BlockParseError(
                    f"line {open_line}: SEARCH block with no preceding 'FILE:' header"
                )
            i += 1
            search: list[str] = []
            while i < len(lines) and lines[i].strip() != DIVIDER:
                if lines[i].strip() == REPLACE_CLOSE:
                    raise BlockParseError(
                        f"line {i + 1}: REPLACE close before '{DIVIDER}' divider"
                    )
                search.append(lines[i])
                i += 1
            if i >= len(lines):
                raise BlockParseError(
                    f"line {open_line}: unterminated SEARCH (no '{DIVIDER}' divider)"
                )
            i += 1  # consume divider
            replace: list[str] = []
            while i < len(lines) and lines[i].strip() != REPLACE_CLOSE:
                if lines[i].strip() == SEARCH_OPEN:
                    raise BlockParseError(
                        f"line {i + 1}: nested SEARCH before '{REPLACE_CLOSE}'"
                    )
                replace.append(lines[i])
                i += 1
            if i >= len(lines):
                raise BlockParseError(
                    f"line {open_line}: unterminated block (no '{REPLACE_CLOSE}')"
                )
            i += 1  # consume close

            blocks.append(Block(
                path=pending_path,
                search="\n".join(search),
                replace="\n".join(replace),
                index=len(blocks),
                line=open_line,
            ))
            # A FILE: header governs until the next one, so consecutive blocks in
            # the same file need only one header.
            continue

        i += 1

    if pending_path and not blocks:
        raise BlockParseError(
            f"line {pending_path_line}: 'FILE: {pending_path}' header with no SEARCH block"
        )
    return blocks


def _normalize_eol(text: str) -> str:
    return text.replace("\r\n", "\n").replace("\r", "\n")


def _count(haystack: str, needle: str) -> int:
    if not needle:
        return 0
    n = 0
    start = 0
    while True:
        at = haystack.find(needle, start)
        if at < 0:
            return n
        n += 1
        start = at + 1  # overlapping-aware; uniqueness is what we care about


def _apply_one(content: str, search: str, replace: str) -> tuple[str | None, str, str]:
    """Return (new_content|None, status, kind)."""
    hits = _count(content, search)
    if hits == 1:
        return content.replace(search, replace, 1), "applied", "exact"
    if hits > 1:
        return None, "nonunique", "exact"

    # Exact miss. Retry modulo line endings before declaring residue.
    norm_content = _normalize_eol(content)
    norm_search = _normalize_eol(search)
    if norm_search != search or norm_content != content:
        hits = _count(norm_content, norm_search)
        if hits == 1:
            new_norm = norm_content.replace(norm_search, _normalize_eol(replace), 1)
            # Restore the file's dominant line ending.
            if "\r\n" in content:
                new_norm = new_norm.replace("\n", "\r\n")
            return new_norm, "applied", "eol"
        if hits > 1:
            return None, "nonunique", "eol"

    return None, "nomatch", "exact"


def apply_blocks(
    blocks: list[Block],
    root: str = ".",
    dry_run: bool = False,
    atomic: bool = False,
) -> dict:
    """Apply blocks grouped by file. Returns a JSON-able report."""
    by_file: dict[str, list[Block]] = {}
    for b in blocks:
        by_file.setdefault(b.path, []).append(b)

    results: list[BlockResult] = []
    staged: dict[str, tuple[str, str]] = {}  # abspath -> (content, encoding-newline)

    for rel, file_blocks in by_file.items():
        abspath = os.path.join(root, rel.replace("/", os.sep))
        try:
            with open(abspath, "r", encoding="utf-8", newline="") as fh:
                content = fh.read()
        except FileNotFoundError:
            for b in file_blocks:
                results.append(BlockResult(rel, b.index, "error", detail="file not found"))
            continue
        except OSError as exc:
            for b in file_blocks:
                results.append(BlockResult(rel, b.index, "error", detail=str(exc)))
            continue

        original = content
        for b in file_blocks:
            new_content, status, kind = _apply_one(content, b.search, b.replace)
            results.append(BlockResult(rel, b.index, status, kind))
            if new_content is not None:
                content = new_content

        if content != original:
            staged[abspath] = (content, rel)

    counts = {"applied": 0, "nomatch": 0, "nonunique": 0, "error": 0}
    kinds = {"exact": 0, "eol": 0}
    for r in results:
        counts[r.status] = counts.get(r.status, 0) + 1
        if r.status == "applied":
            kinds[r.kind] = kinds.get(r.kind, 0) + 1

    residue = [r for r in results if r.status != "applied"]
    clean = not residue
    wrote: list[str] = []

    if atomic and not clean:
        staged = {}

    if not dry_run and staged:
        for abspath, (content, rel) in staged.items():
            with open(abspath, "w", encoding="utf-8", newline="") as fh:
                fh.write(content)
            wrote.append(rel)

    return {
        "blocks": len(blocks),
        "files": len(by_file),
        "counts": counts,
        "applied_kinds": kinds,
        "clean": clean,
        "dry_run": dry_run,
        "atomic": atomic,
        "atomic_aborted": bool(atomic and not clean),
        "wrote": sorted(wrote),
        "results": [dataclasses.asdict(r) for r in results],
        "residue": [dataclasses.asdict(r) for r in residue],
    }


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Deterministic SEARCH/REPLACE applier.")
    ap.add_argument("blocks", nargs="?", default="-",
                    help="path to a .blocks file, or '-' for stdin")
    ap.add_argument("--root", default=".", help="repo root the FILE: paths are relative to")
    ap.add_argument("--dry-run", action="store_true", help="report only; write nothing")
    ap.add_argument("--atomic", action="store_true",
                    help="write nothing unless every block applies cleanly")
    ap.add_argument("--json", dest="json_out", default=None, help="write the report JSON here")
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args(argv)

    text = sys.stdin.read() if args.blocks == "-" else \
        open(args.blocks, "r", encoding="utf-8", newline="").read()

    try:
        blocks = parse_blocks(text)
    except BlockParseError as exc:
        print(f"apply_blocks: PARSE ERROR: {exc}", file=sys.stderr)
        return 2

    if not blocks:
        print("apply_blocks: no blocks found", file=sys.stderr)
        return 2

    report = apply_blocks(blocks, root=args.root, dry_run=args.dry_run, atomic=args.atomic)

    if args.json_out:
        with open(args.json_out, "w", encoding="utf-8") as fh:
            json.dump(report, fh, indent=2)

    if not args.quiet:
        c = report["counts"]
        print(f"apply_blocks: blocks={report['blocks']} files={report['files']} "
              f"applied={c['applied']} nomatch={c['nomatch']} "
              f"nonunique={c['nonunique']} error={c['error']}"
              + (f" (eol-normalized={report['applied_kinds']['eol']})"
                 if report["applied_kinds"].get("eol") else ""))
        for r in report["residue"]:
            print(f"  RESIDUE {r['status']}: {r['path']} block#{r['index']}"
                  + (f" - {r['detail']}" if r["detail"] else ""))

    return 0 if report["clean"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
