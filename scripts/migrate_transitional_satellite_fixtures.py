#!/usr/bin/env python3
"""Stamp explicit allow_compiler_satellites=true on historical plan fixtures.

Production PlanMetaAccess is opt-in (absent → false). Pre-migration fixtures
relied on absent→true. This script makes that transitional contract explicit
without restoring the production default.
"""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TARGETS = [
    ROOT / "python_backend" / "eval" / "fixtures" / "plans",
    ROOT / "python_backend" / "tests" / "fixtures",
    ROOT / "src" / "test" / "resources",
    ROOT / "src" / "main" / "resources",
]
SKIP_NAME_TOKENS = ("no_infer", "mass_only_no_infer", "capability_gap")


def should_skip(path: Path, data: dict) -> str | None:
    name = path.name.lower()
    if any(token in name for token in SKIP_NAME_TOKENS):
        return "name"
    meta = data.get("plan_meta")
    if isinstance(meta, dict) and "allow_compiler_satellites" in meta:
        return "already_set"
    return None


def main() -> None:
    updated: list[str] = []
    skipped: list[tuple[str, str]] = []
    for root in TARGETS:
        if not root.exists():
            continue
        for path in root.rglob("*.json"):
            try:
                text = path.read_text(encoding="utf-8")
            except OSError:
                continue
            if '"components"' not in text:
                continue
            try:
                data = json.loads(text)
            except json.JSONDecodeError:
                continue
            if not isinstance(data, dict) or "components" not in data:
                continue
            reason = should_skip(path, data)
            if reason:
                skipped.append((str(path.relative_to(ROOT)), reason))
                continue
            meta = data.get("plan_meta")
            if not isinstance(meta, dict):
                meta = {}
                data["plan_meta"] = meta
            meta["allow_compiler_satellites"] = True
            meta.setdefault("compiler_satellites_policy", "transitional_fixture")
            path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            updated.append(str(path.relative_to(ROOT)))
    print(f"updated={len(updated)} skipped={len(skipped)}")
    for path in updated[:25]:
        print(f"  + {path}")
    if len(updated) > 25:
        print(f"  ... {len(updated) - 25} more")


if __name__ == "__main__":
    main()
