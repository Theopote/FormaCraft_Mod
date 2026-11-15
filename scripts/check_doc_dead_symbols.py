"""Fail when live docs present deleted types as current implementation.

Source of truth: config/doc_dead_symbols.json (types deleted by the architecture
migration). A live doc line that mentions such a type without a historical /
deletion marker on that line is a violation.

Archived docs (docs/archive/**) are exempt — they are historical snapshots by
definition and must keep their original wording for traceability.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONFIG = ROOT / "config/doc_dead_symbols.json"
DOCS_DIR = ROOT / "docs"
ARCHIVE_DIR_NAME = "archive"


def _line_allowed(line: str, allow: list[str]) -> bool:
    return any(token.lower() in line.lower() for token in allow)


def _blocks(lines: list[str]) -> list[tuple[int, int]]:
    """Split lines into blank-line-separated blocks as (start, end) inclusive."""
    blocks: list[tuple[int, int]] = []
    start: int | None = None
    for i, line in enumerate(lines):
        if line.strip():
            if start is None:
                start = i
        else:
            if start is not None:
                blocks.append((start, i - 1))
                start = None
    if start is not None:
        blocks.append((start, len(lines) - 1))
    return blocks


def _context_allowed(lines: list[str], index: int, allow: list[str]) -> bool:
    """Allow a mention when the surrounding block (or its short lead-in) carries a marker.

    Dead-type mentions are often introduced by a marker line ("以下类已删除：")
    followed by a bullet list. Checking only the exact line produces false
    positives, so the containing block plus a short preceding lead-in block is
    inspected.
    """
    blocks = _blocks(lines)
    current: tuple[int, int] | None = None
    for b in blocks:
        if b[0] <= index <= b[1]:
            current = b
            break
    if current is None:
        return False

    if _line_allowed("\n".join(lines[current[0] : current[1] + 1]), allow):
        return True

    # Short preceding lead-in block (e.g. "以下类已删除：").
    pos = blocks.index(current)
    if pos > 0:
        prev = blocks[pos - 1]
        if prev[1] - prev[0] + 1 <= 3:
            if _line_allowed("\n".join(lines[prev[0] : prev[1] + 1]), allow):
                return True
    return False


def violations(root: Path | None = None, config: dict | None = None) -> list[str]:
    root = root or ROOT
    if config is None:
        config = json.loads(CONFIG.read_text(encoding="utf-8"))
    allow = list(config.get("allow_line_substrings") or [])
    tracked = list(config.get("tracked") or [])
    if not tracked:
        return ["config/doc_dead_symbols.json: tracked list is empty"]

    errors: list[str] = []
    docs_dir = root / "docs"
    if not docs_dir.is_dir():
        return ["docs directory missing"]

    for path in sorted(docs_dir.rglob("*.md")):
        rel_parts = path.relative_to(root).parts
        if ARCHIVE_DIR_NAME in rel_parts:
            continue
        rel = path.relative_to(root).as_posix()
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        for i, line in enumerate(lines, start=1):
            if _line_allowed(line, allow):
                continue
            if _context_allowed(lines, i - 1, allow):
                continue
            for name in tracked:
                if re.search(r"\b" + re.escape(name) + r"\b", line):
                    errors.append(
                        f"{rel}:{i}: live doc references deleted type {name!r} "
                        f"without a historical/deletion marker"
                    )
    return errors


def main() -> int:
    if not CONFIG.is_file():
        print(f"Missing {CONFIG}", file=sys.stderr)
        return 1
    errs = violations()
    if errs:
        for e in errs:
            print(e, file=sys.stderr)
        return 1
    data = json.loads(CONFIG.read_text(encoding="utf-8"))
    print(
        f"Doc dead-symbols OK "
        f"({len(data.get('tracked', []))} deleted types tracked; archived docs exempt)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
