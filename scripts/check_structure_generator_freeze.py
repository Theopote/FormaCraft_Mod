"""Fail if StructureGeneratorRegistry gains new register(...) calls."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REGISTRY = ROOT / "src/main/java/com/formacraft/server/generation/structure/router/StructureGeneratorRegistry.java"
BASELINE = ROOT / "config/structure-generator-baseline.json"
REGISTER = re.compile(r"\bregister\s*\(")


def main() -> int:
    text = REGISTRY.read_text(encoding="utf-8")
    # Count static register invocations in the registry initializer only (rough: all register().
    count = len(REGISTER.findall(text))
    baseline = json.loads(BASELINE.read_text(encoding="utf-8"))
    max_allowed = int(baseline["maxRegisterCalls"])
    print(f"StructureGeneratorRegistry.register calls: {count} (max {max_allowed})")
    if count > max_allowed:
        print(
            f"New structure generators are frozen. Update {BASELINE.name} only with an approved migration.",
            file=sys.stderr,
        )
        return 1
    if count < max_allowed:
        print(
            f"Baseline stale: register count dropped to {count}. Lower maxRegisterCalls in {BASELINE.name}.",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
