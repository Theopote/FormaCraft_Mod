"""P0: patch edit — roof recolor scenario."""

from __future__ import annotations

import json
import unittest
from pathlib import Path


class PatchRoofCultureTest(unittest.TestCase):
    PATCH_PROMPT = "把屋顶换成红色"

    def test_patch_culture_card(self):
        from app.services.keyword_culture_retriever import retrieve

        rag = retrieve(self.PATCH_PROMPT, topK=3, fewShotK=0)
        self.assertTrue(rag.get("hits"))
        self.assertEqual(rag["hits"][0]["id"], "patch_roof_color")
        self.assertEqual(rag.get("patchCardId"), "roof_color_change")
        self.assertTrue(rag.get("llmPlanFewShots"))
        self.assertIsNotNone(rag.get("patchCard"))

    def test_patch_beats_cottage(self):
        from app.services.keyword_culture_retriever import retrieve

        rag = retrieve(self.PATCH_PROMPT, topK=3, fewShotK=0)
        ids = [h["id"] for h in rag.get("hits", [])]
        self.assertEqual(ids[0], "patch_roof_color")

    def test_patch_card_load(self):
        from eval.patch_eval import load_patch_card, resolve_patch_card_id

        self.assertEqual(resolve_patch_card_id(self.PATCH_PROMPT), "roof_color_change")
        card = load_patch_card("roof_color_change")
        assert card is not None
        self.assertEqual(card.get("editKind"), "patch_roof_material")


class PatchRoofEvalTest(unittest.TestCase):
    FIXTURES = Path(__file__).resolve().parent.parent / "eval" / "fixtures" / "plans"

    def _load(self, name: str) -> dict:
        return json.loads((self.FIXTURES / name).read_text(encoding="utf-8"))

    def test_patch_golden_passes_patch_eval(self):
        from eval.patch_eval import evaluate_patch_intent

        plan = self._load("patch_roof_red_golden.json")
        prompt = "把屋顶换成红色"
        results = {name: ok for name, ok, _ in evaluate_patch_intent(plan, prompt)}
        for key in (
            "patch_has_ops",
            "patch_targets_roof",
            "patch_roof_color_red",
            "patch_minimal_scope",
            "patch_no_full_rebuild",
        ):
            self.assertTrue(results.get(key), f"missing or failed: {key}")

    def test_patch_golden_passes_golden_eval(self):
        from eval.golden_eval import evaluate_plan

        plan = self._load("patch_roof_red_golden.json")
        prompt = "把屋顶换成红色"
        result = evaluate_plan(plan, label="patch_roof", prompt=prompt)
        self.assertTrue(result.ok, f"hard failures: {result.hard_failures}")

    def test_patch_ci_scenario(self):
        from eval.golden_eval import run_scenarios

        code = run_scenarios(gate=False, ci_only=True)
        self.assertEqual(code, 0)


if __name__ == "__main__":
    unittest.main()
