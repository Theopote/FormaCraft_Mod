"""P0: siheyuan (四合院) culture card + proportion + eval."""

from __future__ import annotations

import json
import unittest
from pathlib import Path


class SiheyuanCultureTest(unittest.TestCase):
    SIHEYUAN_PROMPT = "盖一个带院子的四合院"

    def test_siheyuan_culture_card(self):
        from app.services.keyword_culture_retriever import retrieve

        rag = retrieve(self.SIHEYUAN_PROMPT, topK=3, fewShotK=0)
        self.assertTrue(rag.get("hits"))
        self.assertEqual(rag["hits"][0]["id"], "siheyuan_courtyard")
        self.assertEqual(rag.get("proportionCardId"), "siheyuan_courtyard")
        self.assertTrue(rag.get("llmPlanFewShots"))

    def test_siheyuan_beats_cottage_on_courtyard_prompt(self):
        from app.services.keyword_culture_retriever import retrieve

        rag = retrieve(self.SIHEYUAN_PROMPT, topK=3, fewShotK=0)
        ids = [h["id"] for h in rag.get("hits", [])]
        self.assertEqual(ids[0], "siheyuan_courtyard")
        if len(ids) > 1:
            self.assertNotEqual(ids[1], "cottage_refined")

    def test_proportion_retriever(self):
        from app.services.proportion_retriever import retrieve_proportion_card

        card = retrieve_proportion_card(self.SIHEYUAN_PROMPT)
        assert card is not None
        self.assertEqual(card.get("id"), "siheyuan_courtyard")
        self.assertIn("courtyard_ratio", card.get("ratios", {}))


class SiheyuanEvalTest(unittest.TestCase):
    FIXTURES = Path(__file__).resolve().parent.parent / "eval" / "fixtures" / "plans"

    def _load(self, name: str) -> dict:
        return json.loads((self.FIXTURES / name).read_text(encoding="utf-8"))

    def test_siheyuan_golden_passes_proportion_eval(self):
        from eval.proportion_eval import evaluate_enclosure, evaluate_proportions

        plan = self._load("siheyuan_courtyard_golden.json")
        prompt = "盖一个带院子的四合院"
        for name, ok, _ in evaluate_proportions(plan, prompt):
            if name == "has_proportion_hints":
                self.assertTrue(ok)
        for name, ok, _ in evaluate_enclosure(plan, prompt):
            self.assertTrue(ok, f"enclosure check failed: {name}")

    def test_siheyuan_golden_passes_golden_eval(self):
        from eval.golden_eval import evaluate_plan

        plan = self._load("siheyuan_courtyard_golden.json")
        prompt = "盖一个带院子的四合院"
        result = evaluate_plan(plan, label="siheyuan", prompt=prompt)
        self.assertTrue(result.ok, f"hard failures: {result.hard_failures}")
        names = {c.name for c in result.checks if c.passed}
        self.assertIn("has_courtyard_component", names)
        self.assertIn("siheyuan_enclosure_layout", names)

    def test_siheyuan_ci_scenario(self):
        from eval.golden_eval import run_scenarios

        code = run_scenarios(gate=False, ci_only=True)
        self.assertEqual(code, 0)


if __name__ == "__main__":
    unittest.main()
