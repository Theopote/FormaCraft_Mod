"""Verify config/generation_entrypoints.json still matches the repo."""

from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONFIG = ROOT / "config/generation_entrypoints.json"
JAVA_ROOT = ROOT / "src/main/java"


def main() -> int:
    if not CONFIG.is_file():
        print(f"Missing {CONFIG}", file=sys.stderr)
        return 1
    data = json.loads(CONFIG.read_text(encoding="utf-8"))
    errors: list[str] = []

    for entry in data.get("java", []):
        cls = entry.get("class", "")
        if not cls:
            errors.append(f"java entry missing class: {entry}")
            continue
        rel = cls.replace(".", "/") + ".java"
        path = JAVA_ROOT / rel
        if not path.is_file():
            errors.append(f"Java class not found: {cls} ({path})")

    py_root = ROOT / "python_backend"
    for entry in data.get("python", []):
        mod = entry.get("module", "").replace(".", "/")
        path = py_root / f"{mod}.py"
        if not path.is_file():
            errors.append(f"Python module not found: {entry.get('module')} ({path})")

    if errors:
        for e in errors:
            print(e, file=sys.stderr)
        return 1
    print(f"Generation entrypoints OK ({len(data.get('java', []))} Java, {len(data.get('python', []))} Python)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
