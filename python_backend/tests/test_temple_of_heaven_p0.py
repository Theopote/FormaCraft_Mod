"""P0: Temple of Heaven (天坛) culture card + proportion + eval."""

from __future__ import annotations

import json
import unittest
from pathlib import Path


class TempleOfHeavenCultureTest(unittest.TestCase):
    TEMPLE_PROMPT = "盖一座天坛"

    def test_temple_culture_card(self):
        from app.services.keyword_culture_retriever import retrieve

        rag = retrieve(self.TEMPLE_PROMPT, topK=3, fewShotK=0)
        self.assertTrue(rag.get("hits"))
        self.assertEqual(rag["hits"][0]["id"], "temple_of_heaven")
        self.assertEqual(rag.get("proportionCardId"), "temple_of_heaven")
        self.assertEqual(rag.get("landmarkModuleId"), "temple_of_heaven")
        self.assertTrue(rag.get("llmPlanFewShots"))

    def test_temple_beats_siheyuan_on_temple_prompt(self):
        from app.services.keyword_culture_retriever import retrieve

        rag = retrieve(self.TEMPLE_PROMPT, topK=3, fewShotK=0)
        ids = [h["id"] for h in rag.get("hits", [])]
        self.assertEqual(ids[0], "temple_of_heaven")

    def test_proportion_retriever(self):
        from app.services.proportion_retriever import retrieve_proportion_card

        card = retrieve_proportion_card(self.TEMPLE_PROMPT)
        assert card is not None
        self.assertEqual(card.get("id"), "temple_of_heaven")
        self.assertIn("tier_count", card.get("ratios", {}))


class TempleOfHeavenEvalTest(unittest.TestCase):
    FIXTURES = Path(__file__).resolve().parent.parent / "eval" / "fixtures" / "plans"

    def _load(self, name: str) -> dict:
        return json.loads((self.FIXTURES / name).read_text(encoding="utf-8"))

    def test_temple_golden_passes_proportion_eval(self):
        from eval.proportion_eval import evaluate_enclosure, evaluate_proportions

        plan = self._load("temple_of_heaven_golden.json")
        prompt = "盖一座天坛"
        for name, ok, _ in evaluate_proportions(plan, prompt):
            if name == "has_proportion_hints":
                self.assertTrue(ok)
        for name, ok, _ in evaluate_enclosure(plan, prompt):
            if name in ("has_enclosure_mass", "temple_has_landmark_module"):
                self.assertTrue(ok, f"enclosure check failed: {name}")

    def test_temple_golden_passes_golden_eval(self):
        from eval.golden_eval import evaluate_plan

        plan = self._load("temple_of_heaven_golden.json")
        prompt = "盖一座天坛"
        result = evaluate_plan(plan, label="temple", prompt=prompt)
        self.assertTrue(result.ok, f"hard failures: {result.hard_failures}")
        names = {c.name for c in result.checks if c.passed}
        self.assertIn("temple_landmark_or_radial_mass", names)
        self.assertIn("temple_radial_layout", names)
        self.assertIn("temple_tier_expressed", names)

    def test_temple_ci_scenario(self):
        from eval.golden_eval import run_scenarios

        code = run_scenarios(gate=False, ci_only=True)
        self.assertEqual(code, 0)


if __name__ == "__main__":
    unittest.main()
