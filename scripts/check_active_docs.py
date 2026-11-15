"""Fail when Tier-0 docs present confirmed-deleted symbols as live guidance.

Only scans authoritative Tier-0 paths from config/active_docs_banned_symbols.json.
Lines that clearly mark history/deletion (见 allow_line_substrings) are exempt.
Does not scan archive / historical manuals.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONFIG = ROOT / "config/active_docs_banned_symbols.json"


def _line_allowed(line: str, allow_substrings: list[str]) -> bool:
    lower = line.lower()
    for token in allow_substrings:
        if token.lower() in lower:
            return True
    return False


def violations(root: Path | None = None, config: dict | None = None) -> list[str]:
    root = root or ROOT
    if config is None:
        config = json.loads(CONFIG.read_text(encoding="utf-8"))
    allow = list(config.get("allow_line_substrings") or [])
    banned = list(config.get("banned") or [])
    errors: list[str] = []

    for rel in config.get("tier0_globs") or []:
        path = root / rel
        if not path.is_file():
            errors.append(f"Tier-0 doc missing: {rel}")
            continue
        text = path.read_text(encoding="utf-8")
        for i, line in enumerate(text.splitlines(), start=1):
            if _line_allowed(line, allow):
                continue
            for item in banned:
                for pattern in item.get("patterns") or []:
                    if pattern in line:
                        errors.append(
                            f"{rel}:{i}: banned live guidance for {item.get('id')} "
                            f"(pattern {pattern!r}). Mark as historical/deleted or remove."
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
        f"Active docs OK ({len(data.get('tier0_globs', []))} files, "
        f"{len(data.get('banned', []))} banned symbol groups)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
