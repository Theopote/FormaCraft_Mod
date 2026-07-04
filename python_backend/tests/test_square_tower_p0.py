"""P0: square five-story tower (五层方塔) culture card + proportion + eval."""

from __future__ import annotations

import json
import unittest
from pathlib import Path


class SquareTowerCultureTest(unittest.TestCase):
    TOWER_PROMPT = "盖一座 5 层的方塔，顶部有平台"

    def test_tower_culture_card(self):
        from app.services.keyword_culture_retriever import retrieve

        rag = retrieve(self.TOWER_PROMPT, topK=3, fewShotK=0)
        self.assertTrue(rag.get("hits"))
        self.assertEqual(rag["hits"][0]["id"], "square_tower_five_story")
        self.assertEqual(rag.get("proportionCardId"), "square_tower_five_story")
        self.assertTrue(rag.get("llmPlanFewShots"))

    def test_tower_beats_castle_on_square_tower_prompt(self):
        from app.services.keyword_culture_retriever import retrieve

        rag = retrieve(self.TOWER_PROMPT, topK=3, fewShotK=0)
        ids = [h["id"] for h in rag.get("hits", [])]
        self.assertEqual(ids[0], "square_tower_five_story")

    def test_proportion_retriever(self):
        from app.services.proportion_retriever import retrieve_proportion_card

        card = retrieve_proportion_card(self.TOWER_PROMPT)
        assert card is not None
        self.assertEqual(card.get("id"), "square_tower_five_story")
        self.assertIn("height_to_width", card.get("ratios", {}))


class SquareTowerEvalTest(unittest.TestCase):
    FIXTURES = Path(__file__).resolve().parent.parent / "eval" / "fixtures" / "plans"

    def _load(self, name: str) -> dict:
        return json.loads((self.FIXTURES / name).read_text(encoding="utf-8"))

    def test_tower_golden_passes_proportion_eval(self):
        from eval.proportion_eval import evaluate_enclosure, evaluate_proportions

        plan = self._load("square_tower_five_story_golden.json")
        prompt = "盖一座 5 层的方塔，顶部有平台"
        for name, ok, _ in evaluate_proportions(plan, prompt):
            if name == "has_proportion_hints":
                self.assertTrue(ok)
        for name, ok, _ in evaluate_enclosure(plan, prompt):
            self.assertTrue(ok, f"enclosure check failed: {name}")

    def test_tower_golden_passes_golden_eval(self):
        from eval.golden_eval import evaluate_plan

        plan = self._load("square_tower_five_story_golden.json")
        prompt = "盖一座 5 层的方塔，顶部有平台"
        result = evaluate_plan(plan, label="tower", prompt=prompt)
        self.assertTrue(result.ok, f"hard failures: {result.hard_failures}")
        names = {c.name for c in result.checks if c.passed}
        self.assertIn("tower_square_footprint", names)
        self.assertIn("tower_top_platform", names)
        self.assertIn("tower_five_floors", names)

    def test_tower_ci_scenario(self):
        from eval.golden_eval import run_scenarios

        code = run_scenarios(gate=False, ci_only=True)
        self.assertEqual(code, 0)


if __name__ == "__main__":
    unittest.main()
