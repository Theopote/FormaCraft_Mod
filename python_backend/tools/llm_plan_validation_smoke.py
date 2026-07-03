"""
Offline smoke tests for LlmPlan Pydantic validation (post-normalization contract).

Run:
  python python_backend/tools/llm_plan_validation_smoke.py
"""

from __future__ import annotations

import sys
from pathlib import Path
from typing import List

_PY_BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(_PY_BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(_PY_BACKEND_ROOT))


def _assert_raises(paths_fragment: str, plan: dict, errors: List[str]) -> None:
    from app.models.llm_plan import LlmPlanValidationError, validate_llm_plan_dict

    try:
        validate_llm_plan_dict(plan)
    except LlmPlanValidationError as exc:
        joined = "; ".join(exc.errors)
        if paths_fragment not in joined:
            errors.append(
                f"expected path fragment '{paths_fragment}' in errors, got: {joined}"
            )
        return
    except Exception as exc:
        errors.append(f"expected LlmPlanValidationError, got {type(exc).__name__}: {exc}")
        return
    errors.append(f"expected validation failure containing '{paths_fragment}', but plan passed")


def _assert_passes(plan: dict, errors: List[str]) -> None:
    from app.models.llm_plan import LlmPlanValidationError, validate_llm_plan_dict

    try:
        validate_llm_plan_dict(plan)
    except LlmPlanValidationError as exc:
        errors.append(f"expected valid plan, got: {'; '.join(exc.errors)}")
    except Exception as exc:
        errors.append(f"expected valid plan, got {type(exc).__name__}: {exc}")


def _minimal_build_plan(**overrides):
    plan = {
        "mode": "build",
        "anchor": {"x": 0, "y": 64, "z": 0},
        "components": [
            {
                "component_type": "MASS_MAIN",
                "relative_position": {"x": 0, "y": 0, "z": 0},
                "dimensions": {"width": 10, "depth": 8, "height": 6},
                "features": [],
                "params": {},
            }
        ],
    }
    plan.update(overrides)
    return plan


def main() -> int:
    errors: List[str] = []

    _assert_passes(_minimal_build_plan(), errors)

    bad_component_request = _minimal_build_plan()
    bad_component_request["components"][0]["features"] = [
        {"component_request": "not-an-object"}
    ]
    _assert_raises("features[0].component_request", bad_component_request, errors)

    bad_string_payload = _minimal_build_plan()
    bad_string_payload["components"][0]["features"] = [
        'component_request:"still-a-string"'
    ]
    _assert_raises("features[0].component_request", bad_string_payload, errors)

    good_component_request = _minimal_build_plan()
    good_component_request["components"][0]["features"] = [
        {"component_request": {"component_type": "WALL", "count": 4}}
    ]
    _assert_passes(good_component_request, errors)

    missing_anchor = {"mode": "build", "components": []}
    _assert_raises("anchor", missing_anchor, errors)

    bad_dimensions = _minimal_build_plan()
    bad_dimensions["components"][0]["dimensions"] = {"width": 0, "depth": 8, "height": 6}
    _assert_raises("width", bad_dimensions, errors)

    patch_without_payload = {
        "mode": "patch",
        "anchor": {"x": 0, "y": 64, "z": 0},
    }
    _assert_raises("patch", patch_without_payload, errors)

    if errors:
        print("FAIL")
        for err in errors:
            print(" - " + err)
        return 1

    print("PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
