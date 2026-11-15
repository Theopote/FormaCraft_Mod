"""Ensure every archived doc opens with a clear "archived / not a wiring guide" banner.

Archived docs keep their original wording for traceability, but a reader who
opens one directly must not mistake it for current implementation. This script
prepends a standard banner (idempotent) and can be re-run after new archives
are added.

Usage:  python scripts/banner_archived_docs.py [--check]
"""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ARCHIVE_ROOTS = (ROOT / "docs/archive",)

BANNER = (
    "> **归档（ARCHIVED）**：本文描述的实现已废弃或已被后续方案取代，**不是现行接线指南**。\n"
    "> 现行流程见 [GENERATION_PIPELINE.md](../../GENERATION_PIPELINE.md) 与 "
    "[ARCHITECTURE.md](../../../ARCHITECTURE.md)；\n"
    "> 迁移与删除台账见 [ARCHITECTURE_AUDIT.md](../../refactoring/ARCHITECTURE_AUDIT.md)。\n"
    "> 保留原因：记录历史设计思路，不代表可恢复的代码位置。\n\n"
)

MARKER = "> **归档（ARCHIVED）**"


def archive_docs() -> list[Path]:
    found: list[Path] = []
    for base in ARCHIVE_ROOTS:
        if not base.is_dir():
            continue
        found.extend(sorted(base.rglob("*.md")))
    return found


def main() -> int:
    check_only = "--check" in sys.argv
    missing: list[Path] = []
    updated: list[Path] = []

    for path in archive_docs():
        text = path.read_text(encoding="utf-8", errors="replace")
        if text.startswith(MARKER):
            continue
        # docs/archive/README.md is the index for the archive itself.
        if path.name == "README.md":
            continue
        missing.append(path)
        if not check_only:
            path.write_text(BANNER + text, encoding="utf-8")
            updated.append(path)

    if check_only:
        for path in missing:
            print(f"missing archive banner: {path.relative_to(ROOT).as_posix()}", file=sys.stderr)
        if missing:
            return 1
        print(f"Archive banners OK ({len(archive_docs())} archived docs)")
        return 0

    print(f"Archive docs scanned: {len(archive_docs())}")
    print(f"Archive banners added: {len(updated)}")
    for path in updated:
        print(f"  {path.relative_to(ROOT).as_posix()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
