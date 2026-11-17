#!/usr/bin/env python3
"""Stamp explicit allow_compiler_satellites=true on *allowed* historical plan fixtures.

Production PlanMetaAccess is opt-in (absent → false). Prefer injecting transitional
policy at test load time (see TransitionalSatellitePlans) rather than mutating
immutable LLM captures or few-shot llmplan_examples.

Whitelist only Java/test resource trees that are not SHA-pinned raw captures and
not model few-shot examples.
"""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# Strict allow-list: never touch complex_build_retest, llmplan_examples, or eval plans
# (eval plans must stay in sync with llmplan_examples for snapshot equality tests).
ALLOWED_ROOTS = [
    ROOT / "src" / "test" / "resources",
]
# Hard denylist substrings (defense in depth).
DENIED_PATH_PARTS = (
    "complex_build_retest",
    "llmplan_examples",
    "eval" + "/fixtures",
    "eval\\fixtures",
    "raw_fixture_sha256",
)
SKIP_NAME_TOKENS = ("no_infer", "mass_only_no_infer", "capability_gap")


def is_denied(path: Path) -> bool:
    text = str(path).replace("\\", "/")
    return any(part.replace("\\", "/") in text for part in DENIED_PATH_PARTS)


def should_skip(path: Path, data: dict) -> str | None:
    if is_denied(path):
        return "denied"
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
    for root in ALLOWED_ROOTS:
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
