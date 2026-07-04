"""ShapeLibrary M1 + window_aspect eval smoke tests."""

from __future__ import annotations

import json
import unittest
from pathlib import Path

_LLMPLAN = (
    Path(__file__).resolve().parent.parent.parent
    / "src/main/resources/assets/formacraft/llmplan_examples/primitive_cylinder.json"
)


class ShapePrimitivePlanTest(unittest.TestCase):
    def test_primitive_cylinder_plan_schema(self):
        from app.models.llm_plan import validate_llm_plan_dict

        data = json.loads(_LLMPLAN.read_text(encoding="utf-8"))
        plan = data.get("plan", data)
        validate_llm_plan_dict(plan)
        comps = plan.get("components") or []
        self.assertEqual(comps[0].get("component_type"), "PRIMITIVE")
        self.assertEqual(comps[0].get("params", {}).get("kind"), "cylinder")

    def test_window_aspect_allowed_in_golden_eval(self):
        from eval.golden_eval import _invalid_facade_params

        comps = [{
            "component_type": "FACADE_WINDOWS",
            "params": {"window_aspect": "vertical_strip", "window_ratio": 0.1},
        }]
        self.assertEqual(_invalid_facade_params(comps), [])


if __name__ == "__main__":
    unittest.main()
