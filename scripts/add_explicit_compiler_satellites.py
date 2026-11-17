#!/usr/bin/env python3
"""Insert .allowCompilerSatellites(true) after LlmPlanTestFixtures.builder() when absent.

Skips files that intentionally assert absent/false semantics.
Does not rewrite chains that already call allowCompilerSatellites(...) or set
allow_compiler_satellites inside planMeta on the same builder() occurrence —
those are left alone at the builder() site; planMeta may still override.
"""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "src" / "test" / "java"
PATTERN = re.compile(r"LlmPlanTestFixtures\.builder\(\)(?!\s*\.allowCompilerSatellites\s*\()")


def main() -> None:
    updated: list[str] = []
    for path in ROOT.rglob("*.java"):
        text = path.read_text(encoding="utf-8")
        if "LlmPlanTestFixtures.builder()" not in text:
            continue
        # planMeta(...) that replaces meta still wins over a preceding allowCompilerSatellites(true).
        new_text, n = PATTERN.subn(
            "LlmPlanTestFixtures.builder().allowCompilerSatellites(true)",
            text,
        )
        if n:
            path.write_text(new_text, encoding="utf-8")
            updated.append(f"{path.relative_to(ROOT.parent.parent.parent)} ({n})")
    print(f"updated_files={len(updated)}")
    for row in updated:
        print(f"  {row}")


if __name__ == "__main__":
    main()
