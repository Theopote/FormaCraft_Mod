"""P0: siheyuan (四合院) culture card + proportion + eval."""

from __future__ import annotations

import json
import unittest
from pathlib import Path


class SiheyuanCultureTest(unittest.TestCase):
    SIHEYUAN_PROMPT = "盖一个带院子的四合院"

    def test_regional_identities_and_refusals(self):
        from app.services.keyword_culture_retriever import retrieve
        from app.services.style_identity import canonical_style_id
        from app.services.style_profile_registry import get_style_profile

        cases = [
            ("北京四合院", "siheyuan_courtyard", "Chinese_Vernacular_Siheyuan"),
            ("siheyuan", "siheyuan_courtyard", "Chinese_Vernacular_Siheyuan"),
            ("徽派民居", "huizhou_vernacular", "Chinese_Vernacular_Huizhou"),
            ("Huizhou dwelling", "huizhou_vernacular", "Chinese_Vernacular_Huizhou"),
        ]
        for prompt, card_id, style_id in cases:
            with self.subTest(prompt=prompt):
                hit = retrieve(prompt, topK=1, fewShotK=0)["hits"][0]
                self.assertEqual((hit["id"], hit["styleId"]), (card_id, style_id))
                self.assertIsNotNone(get_style_profile(style_id))
        self.assertEqual(canonical_style_id("北京四合院"), "Chinese_Vernacular_Siheyuan")
        for prompt, forbidden in [("要四合院，不要徽派", "Chinese_Vernacular_Huizhou"),
                                  ("要徽派，不要四合院", "Chinese_Vernacular_Siheyuan"),
                                  ("Huizhou dwelling, no siheyuan", "Chinese_Vernacular_Siheyuan"),
                                  ("siheyuan, no Huizhou", "Chinese_Vernacular_Huizhou")]:
            with self.subTest(prompt=prompt):
                self.assertNotIn(forbidden, [h["styleId"] for h in retrieve(prompt, topK=100, fewShotK=0)["hits"]])

    def test_siheyuan_example_does_not_inherit_huizhou_ornaments(self):
        from app.services.keyword_culture_retriever import retrieve
        from app.services.style_profile_registry import get_style_profile
        assets = Path(__file__).resolve().parents[2] / "src/main/resources/assets/formacraft"
        example = json.loads((assets / "llmplan_examples/siheyuan_courtyard_compositional.json").read_text(encoding="utf-8"))["plan"]
        self.assertEqual(example["style_profile"], "Chinese_Vernacular_Siheyuan")
        self.assertNotIn("horse_head_gable", example["style_attributes"]["decorative_elements"])
        defaults = get_style_profile(example["style_profile"]).defaults
        self.assertEqual(defaults["materials"]["wall"], ["minecraft:stone_bricks"])
        self.assertNotIn("ornament_profile", defaults["components"])
        self.assertEqual(retrieve(self.SIHEYUAN_PROMPT)["hits"][0]["styleId"], example["style_profile"])

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
