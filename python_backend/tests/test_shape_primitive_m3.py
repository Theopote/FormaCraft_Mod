"""ShapeLibrary M3 — plate / voronoi / mobius + opening grammar smoke tests."""

from __future__ import annotations

import json
import unittest
from pathlib import Path

_EXAMPLES = Path(__file__).resolve().parent.parent.parent / "src/main/resources/assets/formacraft/llmplan_examples"


class ShapePrimitiveM3Test(unittest.TestCase):
    def _load(self, name: str) -> dict:
        data = json.loads((_EXAMPLES / name).read_text(encoding="utf-8"))
        return data.get("plan", data)

    def test_voronoi_plate_plan_schema(self):
        from app.models.llm_plan import validate_llm_plan_dict

        plan = self._load("primitive_voronoi_plate.json")
        validate_llm_plan_dict(plan)
        params = (plan.get("components") or [])[0].get("params", {})
        self.assertEqual(params.get("kind"), "voronoi")
        self.assertEqual(params.get("extrude_mode"), "plate")

    def test_mobius_plan_schema(self):
        from app.models.llm_plan import validate_llm_plan_dict

        plan = self._load("primitive_mobius.json")
        validate_llm_plan_dict(plan)
        self.assertEqual(
            (plan.get("components") or [])[0].get("params", {}).get("kind"),
            "mobius",
        )

    def test_proportion_eval_window_aspect(self):
        from eval.proportion_eval import evaluate_proportions

        examples = Path(__file__).resolve().parent.parent.parent / "src/main/resources/assets/formacraft/llmplan_examples"
        plan = json.loads((examples / "cottage_refined_small.json").read_text(encoding="utf-8")).get("plan", {})
        checks = evaluate_proportions(plan, prompt="小房子")
        names = {c[0] for c in checks}
        self.assertIn("has_proportion_hints", names)


class OpeningGrammarM3Test(unittest.TestCase):
    def test_cottage_plan_has_window_aspect_hint(self):
        plan = json.loads(
            (_EXAMPLES / "cottage_refined_small.json").read_text(encoding="utf-8")
        ).get("plan", {})
        hints = plan.get("proportion_hints") or {}
        self.assertEqual(hints.get("window_aspect"), "square")
        facade = next(
            c for c in (plan.get("components") or [])
            if c.get("component_type") == "FACADE_WINDOWS"
        )
        self.assertEqual(facade.get("params", {}).get("window_aspect"), "square")


if __name__ == "__main__":
    unittest.main()
