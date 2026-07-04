"""M3+ — Voronoi 3D, Möbius CSG, void_ratio clamp smoke tests."""

from __future__ import annotations

import json
import unittest
from pathlib import Path

_EXAMPLES = Path(__file__).resolve().parent.parent.parent / "src/main/resources/assets/formacraft/llmplan_examples"


class ShapeAdvancedM3PlusTest(unittest.TestCase):
    def _load(self, name: str) -> dict:
        data = json.loads((_EXAMPLES / name).read_text(encoding="utf-8"))
        return data.get("plan", data)

    def test_voronoi_3d_plan_schema(self):
        from app.models.llm_plan import validate_llm_plan_dict

        plan = self._load("primitive_voronoi_3d.json")
        validate_llm_plan_dict(plan)
        params = (plan.get("components") or [])[0].get("params", {})
        self.assertTrue(params.get("voronoi_3d"))

    def test_mobius_csg_plan_schema(self):
        from app.models.llm_plan import validate_llm_plan_dict

        plan = self._load("primitive_mobius_csg.json")
        validate_llm_plan_dict(plan)
        ops = (plan.get("components") or [])[0].get("params", {}).get("operations", [])
        self.assertEqual(len(ops), 2)
        self.assertEqual(ops[1].get("kind"), "mobius")

    def test_proportion_eval_void_ratio_ceiling(self):
        from eval.proportion_eval import evaluate_proportions

        plan = {
            "mode": "build",
            "anchor": {"x": 0, "y": 64, "z": 0},
            "proportion_hints": {"typology": "cottage", "max_void_ratio": 0.12},
            "components": [{
                "component_type": "MASS_MAIN",
                "relative_position": {"x": 0, "y": 0, "z": 0},
                "dimensions": {"width": 7, "depth": 7, "height": 5},
                "params": {"void_ratio": 0.4},
            }],
        }
        checks = evaluate_proportions(plan, card_id="cottage_refined")
        void_checks = [c for c in checks if c[0] == "void_ratio_typology"]
        self.assertTrue(void_checks)
        self.assertFalse(void_checks[0][1])


if __name__ == "__main__":
    unittest.main()
