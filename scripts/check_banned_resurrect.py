"""Block resurrection of deleted routing/prompt/edit entrypoints in production code."""

from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONFIG = ROOT / "config/banned_resurrect_symbols.json"


def _allowed(path: Path, allow_substrings: list[str]) -> bool:
    s = str(path).replace("\\", "/")
    for token in allow_substrings:
        if token.replace("\\", "/") in s:
            return True
    return False


def violations(root: Path | None = None, config: dict | None = None) -> list[str]:
    root = root or ROOT
    if config is None:
        config = json.loads(CONFIG.read_text(encoding="utf-8"))
    banned = list(config.get("banned_substrings") or [])
    allow = list(config.get("allow_path_substrings") or [])
    errors: list[str] = []

    roots: list[Path] = []
    for rel in list(config.get("java_roots") or []) + list(config.get("python_roots") or []):
        roots.append(root / rel)

    for base in roots:
        if not base.is_dir():
            continue
        for path in base.rglob("*"):
            if path.suffix not in {".java", ".py"}:
                continue
            if _allowed(path, allow):
                continue
            text = path.read_text(encoding="utf-8", errors="replace")
            for needle in banned:
                if needle in text:
                    rel = path.relative_to(root).as_posix()
                    errors.append(f"{rel}: banned resurrect symbol {needle!r}")
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
    print(f"Banned-resurrect OK ({len(data.get('banned_substrings', []))} patterns)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
