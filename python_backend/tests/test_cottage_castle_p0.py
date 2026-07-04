"""P0: cottage + castle culture cards, proportion cards, eval."""

from __future__ import annotations

import json
import unittest
from pathlib import Path


class CottageCastleCultureTest(unittest.TestCase):
    COTTAGE_PROMPT = "盖一个 7x7 的小石头房子，带门和窗"
    CASTLE_PROMPT = "盖一座中世纪石头城堡，有塔楼和城墙"

    def test_cottage_culture_card(self):
        from app.services.keyword_culture_retriever import retrieve

        rag = retrieve(self.COTTAGE_PROMPT, topK=2, fewShotK=0)
        self.assertTrue(rag.get("hits"))
        self.assertEqual(rag["hits"][0]["id"], "cottage_refined")
        self.assertEqual(rag.get("proportionCardId"), "cottage_refined")
        self.assertTrue(rag.get("llmPlanFewShots"))

    def test_castle_culture_card(self):
        from app.services.keyword_culture_retriever import retrieve

        rag = retrieve(self.CASTLE_PROMPT, topK=2, fewShotK=0)
        self.assertTrue(rag.get("hits"))
        self.assertEqual(rag["hits"][0]["id"], "medieval_castle")
        self.assertEqual(rag.get("proportionCardId"), "castle_wall")
        self.assertTrue(rag.get("llmPlanFewShots"))

    def test_proportion_retriever(self):
        from app.services.proportion_retriever import retrieve_proportion_card

        cottage = retrieve_proportion_card(self.COTTAGE_PROMPT)
        assert cottage is not None
        self.assertEqual(cottage.get("id"), "cottage_refined")
        self.assertIn("height_to_width", cottage.get("ratios", {}))

        castle = retrieve_proportion_card(self.CASTLE_PROMPT)
        assert castle is not None
        self.assertEqual(castle.get("id"), "castle_wall")


class ProportionEvalTest(unittest.TestCase):
    FIXTURES = Path(__file__).resolve().parent.parent / "eval" / "fixtures" / "plans"

    def _load(self, name: str) -> dict:
        return json.loads((self.FIXTURES / name).read_text(encoding="utf-8"))

    def test_cottage_golden_passes_proportion_eval(self):
        from eval.proportion_eval import evaluate_enclosure, evaluate_proportions

        plan = self._load("cottage_refined_golden.json")
        prompt = "盖一个 7x7 的小石头房子，带门和窗"
        for name, ok, _ in evaluate_proportions(plan, prompt):
            self.assertTrue(ok, f"proportion check failed: {name}")
        for name, ok, _ in evaluate_enclosure(plan, prompt):
            self.assertTrue(ok, f"enclosure check failed: {name}")

    def test_castle_golden_passes_proportion_eval(self):
        from eval.proportion_eval import evaluate_enclosure, evaluate_proportions

        plan = self._load("medieval_castle_golden.json")
        prompt = "盖一座中世纪石头城堡，有塔楼和城墙"
        for name, ok, _ in evaluate_proportions(plan, prompt):
            if name == "has_proportion_hints":
                self.assertTrue(ok)
        for name, ok, _ in evaluate_enclosure(plan, prompt):
            self.assertTrue(ok, f"enclosure check failed: {name}")

    def test_scenarios_runner(self):
        from eval.golden_eval import run_scenarios

        code = run_scenarios(gate=False)
        self.assertEqual(code, 0)


if __name__ == "__main__":
    unittest.main()
