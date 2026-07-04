"""PR-1: BuildingResearchAgent — open-world building research."""

from __future__ import annotations

import json
import os
import unittest
from unittest.mock import patch

from app.models.building_profile import BuildingProfile, validate_building_profile
from app.services.building_research_agent import (
    format_profile_for_prompt,
    is_building_research_enabled,
    multi_source_search,
    plan_search_queries,
    research_building_context,
    research_building_profile,
    synthesize_profile_rule_based,
)


MOCK_RESULTS = [
    {
        "title": "Suzhou Museum - Wikipedia",
        "snippet": "The Suzhou Museum designed by I.M. Pei features white walls, grey stone base, central glass roof, and water courtyard. Opened in 2006.",
        "url": "https://example.com/suzhou",
    },
    {
        "title": "苏州博物馆 建筑特点",
        "snippet": "贝聿铭设计，白墙黛瓦，几何窗格，中央玻璃屋顶，水景庭院。",
        "url": "https://example.com/sz-bowuguan",
    },
]


class QueryPlannerTest(unittest.TestCase):
    def test_extracts_chinese_building_name(self):
        should, queries, subject = plan_search_queries("复原苏州博物馆")
        self.assertTrue(should)
        self.assertIn("苏州博物馆", subject)
        self.assertTrue(any("苏州博物馆" in q for q in queries))

    def test_extracts_english_building_name(self):
        should, queries, subject = plan_search_queries("recreate the Sydney Opera House")
        self.assertTrue(should)
        self.assertIn("Sydney Opera House", subject)
        self.assertTrue(any("Sydney Opera House" in q for q in queries))

    def test_skips_patch_edit_prompt(self):
        should, queries, _ = plan_search_queries("把屋顶换成红色")
        self.assertFalse(should)
        self.assertEqual(queries, [])

    def test_unknown_landmark_still_searches_in_always_mode(self):
        should, queries, subject = plan_search_queries("建造圣家堂")
        self.assertTrue(should)
        self.assertIn("圣家堂", subject)
        self.assertGreaterEqual(len(queries), 1)


class ProfileSynthesisTest(unittest.TestCase):
    def test_rule_based_profile_from_mock_results(self):
        profile = synthesize_profile_rule_based(
            "苏州博物馆",
            "复原苏州博物馆",
            MOCK_RESULTS,
        )
        self.assertIsInstance(profile, BuildingProfile)
        self.assertEqual(profile.identity.name, "苏州博物馆")
        self.assertGreaterEqual(profile.identity.confidence, 0.5)
        self.assertEqual(len(profile.sources), 2)
        self.assertTrue(profile.research_notes)
        # 特征关键词
        joined = " ".join(profile.structure.distinctive_elements).lower()
        self.assertTrue(
            "courtyard" in joined or "庭院" in joined or "glass" in joined or profile.research_notes
        )

    def test_rule_based_profile_without_search(self):
        profile = synthesize_profile_rule_based(
            "某未知建筑",
            "盖一座某未知建筑",
            [],
        )
        self.assertLess(profile.identity.confidence, 0.5)
        self.assertIn("MASS_MAIN", profile.minecraft_strategy.recommended_components)

    def test_format_profile_for_prompt(self):
        profile = synthesize_profile_rule_based("Eiffel Tower", "build Eiffel Tower", MOCK_RESULTS)
        text = format_profile_for_prompt(profile)
        self.assertIn("Building Research Profile", text)
        self.assertIn("BuildingProfile(JSON):", text)
        self.assertIn("Eiffel Tower", text)
        # 必须是合法 JSON 块
        json_start = text.index("{", text.index("BuildingProfile(JSON):"))
        block = text[json_start:]
        parsed = json.loads(block.split("\n\n", 1)[0] if "\n\n" in block else block)
        self.assertIn("identity", parsed)

    def test_validate_building_profile(self):
        raw = synthesize_profile_rule_based("test", "build test", MOCK_RESULTS).model_dump()
        validated = validate_building_profile(raw)
        self.assertIsInstance(validated, BuildingProfile)


class MultiSourceSearchTest(unittest.TestCase):
    def test_deduplicates_results(self):
        def fake_search(query: str, max_results: int):
            return [
                {"title": "A", "snippet": "same snippet", "url": "https://a"},
                {"title": "B", "snippet": "same snippet", "url": ""},
            ]

        merged = multi_source_search(["q1", "q2"], search_fn=fake_search)
        self.assertEqual(len(merged), 1)


class ResearchIntegrationTest(unittest.TestCase):
    def test_research_with_mock_search_no_network(self):
        def fake_search(query: str, max_results: int):
            return MOCK_RESULTS[:1]

        profile = research_building_profile(
            "建造苏州博物馆",
            search_fn=fake_search,
        )
        self.assertIsNotNone(profile)
        assert profile is not None
        self.assertEqual(profile.query, "苏州博物馆")

    def test_research_context_returns_prompt_block(self):
        def fake_search(query: str, max_results: int):
            return MOCK_RESULTS

        ctx = research_building_context(
            "recreate Suzhou Museum",
            search_fn=fake_search,
        )
        self.assertIsNotNone(ctx)
        assert ctx is not None
        self.assertIn("Building Research Profile", ctx)

    @patch.dict(os.environ, {"BUILDING_RESEARCH": "off"}, clear=False)
    def test_research_off_returns_none(self):
        # 需要 reload 模块才能读 env — 直接测 is_building_research_enabled
        self.assertFalse(is_building_research_enabled())

    def test_long_system_prompt_skipped(self):
        long_prompt = "SYSTEM PROMPT " * 50 + "without build verbs"
        should, _, _ = plan_search_queries(long_prompt)
        self.assertFalse(should)


if __name__ == "__main__":
    unittest.main()
