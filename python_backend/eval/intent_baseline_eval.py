"""Offline intent-classification baseline (no live LLM).

Usage:
    python -m eval.intent_baseline_eval
    python -m eval.intent_baseline_eval --gate   # exit 1 on any failure
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any, Dict, List, Tuple

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.services.request_intent import extract_keyword_intent, merge_request_intent  # noqa: E402

FIXTURE = Path(__file__).resolve().parent / "fixtures" / "intent_baseline_v1.json"


def _check_case(case: Dict[str, Any]) -> List[str]:
    prompt = str(case.get("prompt") or "")
    intent = extract_keyword_intent(prompt)
    # Optional: simulate LLM declared_intent override when fixture provides it.
    llm = case.get("llm_declared")
    if isinstance(llm, dict):
        intent = merge_request_intent(intent, llm)

    errors: List[str] = []
    expected_cls = case.get("building_class")
    if expected_cls is not None and intent.get("building_class") != expected_cls:
        errors.append(
            f"building_class: got {intent.get('building_class')!r} expected {expected_cls!r}"
        )

    if case.get("tower_subtype") and intent.get("tower_subtype") != case["tower_subtype"]:
        errors.append(
            f"tower_subtype: got {intent.get('tower_subtype')!r} expected {case['tower_subtype']!r}"
        )

    if case.get("composition") and intent.get("composition") != case["composition"]:
        errors.append(
            f"composition: got {intent.get('composition')!r} expected {case['composition']!r}"
        )

    if case.get("roof_type") and intent.get("roof_type") != case["roof_type"]:
        errors.append(f"roof_type: got {intent.get('roof_type')!r} expected {case['roof_type']!r}")

    forbids = list(intent.get("forbids") or [])
    if case.get("forbids_contains") and case["forbids_contains"] not in forbids:
        errors.append(f"forbids missing {case['forbids_contains']!r}: {forbids}")
    if case.get("forbids_excludes") and case["forbids_excludes"] in forbids:
        errors.append(f"forbids unexpectedly has {case['forbids_excludes']!r}: {forbids}")

    roof_forbids = list(intent.get("roof_forbids") or [])
    if case.get("roof_forbids_contains") and case["roof_forbids_contains"] not in roof_forbids:
        errors.append(
            f"roof_forbids missing {case['roof_forbids_contains']!r}: {roof_forbids}"
        )

    if case.get("materials_wall"):
        mats = intent.get("materials") or {}
        if mats.get("wall") != case["materials_wall"]:
            errors.append(f"materials.wall: got {mats.get('wall')!r}")

    if case.get("footprint"):
        fp = intent.get("footprint") or {}
        exp = case["footprint"]
        if [fp.get("width"), fp.get("depth")] != list(exp):
            errors.append(f"footprint: got {fp!r} expected {exp!r}")

    return errors


def run_baseline() -> Tuple[int, int, List[Tuple[str, List[str]]]]:
    data = json.loads(FIXTURE.read_text(encoding="utf-8"))
    cases = data.get("cases") or []
    failed: List[Tuple[str, List[str]]] = []
    for case in cases:
        if not isinstance(case, dict):
            continue
        cid = str(case.get("id") or case.get("prompt") or "?")
        errs = _check_case(case)
        if errs:
            failed.append((cid, errs))
    return len(cases), len(failed), failed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gate", action="store_true", help="exit 1 if any case fails")
    args = parser.parse_args()
    total, n_fail, failed = run_baseline()
    print(f"intent_baseline: {total - n_fail}/{total} passed")
    for cid, errs in failed:
        print(f"  FAIL {cid}:")
        for e in errs:
            print(f"    - {e}")
    if args.gate and n_fail:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
