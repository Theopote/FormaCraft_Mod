"""ShapeLibrary M2 — sphere / CSG / extended params schema smoke tests."""

from __future__ import annotations

import json
import unittest
from pathlib import Path

_EXAMPLES = Path(__file__).resolve().parent.parent.parent / "src/main/resources/assets/formacraft/llmplan_examples"


class ShapePrimitiveM2Test(unittest.TestCase):
    def _load(self, name: str) -> dict:
        data = json.loads((_EXAMPLES / name).read_text(encoding="utf-8"))
        return data.get("plan", data)

    def test_sphere_plan_schema(self):
        from app.models.llm_plan import validate_llm_plan_dict

        plan = self._load("primitive_sphere.json")
        validate_llm_plan_dict(plan)
        comp = (plan.get("components") or [])[0]
        self.assertEqual(comp.get("component_type"), "PRIMITIVE")
        self.assertEqual(comp.get("params", {}).get("kind"), "sphere")

    def test_csg_subtract_plan_schema(self):
        from app.models.llm_plan import validate_llm_plan_dict

        plan = self._load("primitive_csg_box_cylinder.json")
        validate_llm_plan_dict(plan)
        params = (plan.get("components") or [])[0].get("params", {})
        self.assertIn("subtract", params)
        self.assertEqual(params["subtract"].get("kind"), "cylinder")

    def test_operations_array_plan_validates(self):
        from app.models.llm_plan import validate_llm_plan_dict

        plan = {
            "mode": "build",
            "style_profile": "DEFAULT",
            "anchor": {"x": 0, "y": 64, "z": 0},
            "global_constraints": {"facing": "SOUTH", "symmetry": "NONE", "terrain_strategy": "ADAPTIVE"},
            "layout": {"skeleton_type": "COMPOUND", "path_based": False, "slots": []},
            "components": [{
                "component_type": "PRIMITIVE",
                "relative_position": {"x": 0, "y": 0, "z": 0},
                "dimensions": {"width": 12, "depth": 12, "height": 12},
                "params": {
                    "kind": "box",
                    "operations": [
                        {"op": "union", "kind": "box"},
                        {"op": "subtract", "kind": "cylinder", "radius": 4},
                    ],
                },
            }],
        }
        validate_llm_plan_dict(plan)


if __name__ == "__main__":
    unittest.main()
