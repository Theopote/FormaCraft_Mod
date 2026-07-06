"""PR-A: no migrated MODULE few-shots in RAG; typology-first research/plan prompts."""

from __future__ import annotations

import json
import unittest

from app.models.building_profile import BuildingProfile, ProfileMinecraftStrategy
from app.services.building_plan_stage import research_landmark_override_block
from app.services.building_research_agent import (
    finalize_profile_minecraft_strategy,
    format_profile_for_prompt,
)


def _components_from_few_shots(rag: dict) -> list[dict]:
    out: list[dict] = []
    for ex in rag.get("llmPlanFewShots") or []:
        if not isinstance(ex, dict):
            continue
        plan = ex.get("plan") if isinstance(ex.get("plan"), dict) else ex
        if not isinstance(plan, dict):
            continue
        comps = plan.get("components")
        if isinstance(comps, list):
            out.extend(c for c in comps if isinstance(c, dict))
    return out


class PrACultureFewshotTest(unittest.TestCase):
    CASES = [
        ("法门寺塔", "famen_pagoda"),
        ("佛光寺大殿", "foguang_temple_hall"),
        ("盖一座天坛", "temple_of_heaven"),
        ("大雁塔", "giant_wild_goose_pagoda"),
    ]

    def test_migrated_culture_cards_no_module_fewshots(self):
        from app.services.keyword_culture_retriever import retrieve

        for prompt, card_id in self.CASES:
            with self.subTest(prompt=prompt, card_id=card_id):
                rag = retrieve(prompt, topK=1, fewShotK=5)
                self.assertEqual(rag["hits"][0]["id"], card_id)
                comps = _components_from_few_shots(rag)
                self.assertTrue(comps, f"expected typology few-shots for {card_id}")
                blob = json.dumps(rag.get("llmPlanFewShots") or [], ensure_ascii=False).lower()
                self.assertNotIn("_module.json", blob)
                for comp in comps:
                    self.assertNotEqual(
                        str(comp.get("component_type") or "").upper(),
                        "MODULE",
                        msg=f"{card_id} few-shot must not use MODULE",
                    )
                    for feat in comp.get("features") or []:
                        self.assertFalse(
                            str(feat).lower().startswith("landmark:"),
                            msg=f"{card_id} few-shot must not use landmark: feature",
                        )

    def test_birds_nest_typology_fewshot(self):
        from app.services.keyword_culture_retriever import retrieve

        rag = retrieve("建一座鸟巢体育馆", topK=1, fewShotK=3)
        blob = json.dumps(rag.get("llmPlanFewShots") or [], ensure_ascii=False)
        self.assertTrue(rag.get("llmPlanFewShots"))
        self.assertIn("stadium_bowl", blob.lower())
        self.assertNotIn("_module.json", blob.lower())

    def test_golden_gate_typology_fewshot(self):
        from app.services.keyword_culture_retriever import retrieve

        rag = retrieve("生成旧金山金门大桥", topK=1, fewShotK=3)
        blob = json.dumps(rag.get("llmPlanFewShots") or [], ensure_ascii=False)
        self.assertTrue(rag.get("llmPlanFewShots"))
        self.assertIn("suspension_bridge", blob.lower())
        self.assertNotIn("_module.json", blob.lower())

    def test_gothic_cathedral_typology_fewshot(self):
        from app.services.keyword_culture_retriever import retrieve

        rag = retrieve("生成一座哥特大教堂", topK=1, fewShotK=3)
        blob = json.dumps(rag.get("llmPlanFewShots") or [], ensure_ascii=False)
        self.assertTrue(rag.get("llmPlanFewShots"))
        self.assertIn("gothic_cathedral_hall", blob.lower())
        self.assertNotIn("_module.json", blob.lower())

    def test_mingqing_courtyard_typology_fewshot(self):
        from app.services.keyword_culture_retriever import retrieve

        rag = retrieve("建造一座明清官式院落", topK=1, fewShotK=3)
        blob = json.dumps(rag.get("llmPlanFewShots") or [], ensure_ascii=False)
        self.assertTrue(rag.get("llmPlanFewShots"))
        self.assertIn("courtyard_compound", blob.lower())
        self.assertNotIn("_module.json", blob.lower())


class PrAResearchPromptTest(unittest.TestCase):
    TEMPLE_PROMPT = "盖一座天坛"

    def test_finalize_temple_profile_typology_first(self):
        profile = finalize_profile_minecraft_strategy(
            BuildingProfile(
                query=self.TEMPLE_PROMPT,
                minecraft_strategy=ProfileMinecraftStrategy(),
            ),
            self.TEMPLE_PROMPT,
        )
        mc = profile.minecraft_strategy
        self.assertEqual(mc.structural_typology, "radial_terrace_hall")
        self.assertIsNone(mc.landmark_module)
        self.assertEqual(mc.reference_landmark, "temple_of_heaven")
        self.assertIn("STRUCTURE", mc.recommended_components)

    def test_format_profile_typology_routing_text(self):
        profile = finalize_profile_minecraft_strategy(
            BuildingProfile(
                query=self.TEMPLE_PROMPT,
                minecraft_strategy=ProfileMinecraftStrategy(),
            ),
            self.TEMPLE_PROMPT,
        )
        text = format_profile_for_prompt(profile)
        self.assertIn("typology-first", text.lower())
        self.assertIn("temple_of_heaven", text)
        self.assertIn("NEVER use MODULE", text)

    def test_plan_stage_override_typology_not_module(self):
        profile = finalize_profile_minecraft_strategy(
            BuildingProfile(
                query=self.TEMPLE_PROMPT,
                minecraft_strategy=ProfileMinecraftStrategy(),
            ),
            self.TEMPLE_PROMPT,
        )
        block = research_landmark_override_block(profile)
        self.assertIn("typology:radial_terrace_hall", block)
        self.assertIn("Do NOT output MODULE", block)
        self.assertNotIn('landmark_module = "temple_of_heaven"', block)


if __name__ == "__main__":
    unittest.main()
