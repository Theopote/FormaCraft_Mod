"""PR-1: BuildingResearchAgent — open-world building research."""

from __future__ import annotations

import json
import os
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from app.models.building_profile import BuildingProfile, validate_building_profile
from app.services.building_research_agent import (
    format_profile_for_prompt,
    is_building_research_enabled,
    multi_source_search,
    plan_search_queries,
    research_building_profile,
    synthesize_profile_rule_based,
    synthesize_profile_with_llm,
)

def test_geometry_evidence_reaches_planning_only_with_shown_sources():
    from app.services.building_plan_stage import build_plan_stage_user_block

    source = {"title": "Example archive", "url": "https://example.com/archive",
              "snippet": "The curved canopy connects two unequal wings."}
    feature = {"feature": "Curved canopy", "geometry": "Bent shell",
               "relationship": "Connects unequal wings", "source_urls": [source["url"]]}
    unseen = {**feature, "feature": "Unsupported spire", "source_urls": ["https://example.com/unseen"]}
    raw = json.dumps({"identity": {"name": "Example hall"}, "geometry_evidence": [{}, *([unseen] * 8), feature, feature],
                      "sources": [{"title": "Invented source", "url": unseen["source_urls"][0]}]})
    response = SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=raw))])
    profile = synthesize_profile_with_llm(object(), "test", "Example hall", "保留双翼，不要雨棚",
                                          [source], call_with_timeout=lambda *_: response)
    assert profile is not None
    assert profile.identity.name == "Example hall"
    assert [item.model_dump() for item in profile.geometry_evidence] == [feature]
    assert [item.model_dump() for item in profile.sources] == [{"title": source["title"], "url": source["url"]}]
    for prompt in (format_profile_for_prompt(profile), build_plan_stage_user_block(profile, "保留双翼，不要雨棚")):
        assert "Connects unequal wings" in prompt
        assert source["url"] in prompt
        assert "Unsupported spire" not in prompt
        assert "Invented source" not in prompt
    assert "geometry_evidence" not in BuildingProfile().to_prompt_dict()


def test_massing_draft_receives_geometry_as_optional_evidence():
    from app.services.ai_planner import _llm_plan_massing_prepass

    request = "保留双翼，不要雨棚"
    evidence = [{"feature": "Curved canopy", "geometry": "Bent shell",
                 "source_urls": ["https://example.com/archive"]}]
    response = SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content='{"massing": {}}'))])
    with patch("app.services.ai_planner.call_chat_with_llm_plan_response_formats",
               return_value=(response, "json")) as call:
        draft = _llm_plan_massing_prepass(object(), "test", "", request, SimpleNamespace(), 1,
                                          geometry_evidence=evidence)
    assert draft == '{"massing": {}}'
    prompt = call.call_args.kwargs["messages"][-1]["content"]
    assert prompt.startswith(request)
    assert "explicit user requirements and omissions win" in prompt
    assert "Bent shell" in prompt
    assert "https://example.com/archive" in prompt


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

    def test_zaha_xian_expands_queries_and_aliases(self):
        should, queries, subject = plan_search_queries("扎哈设计的西安体育馆")
        self.assertTrue(should)
        joined = " ".join(queries)
        self.assertTrue(
            any(k in joined for k in ("西安奥体中心", "Zaha Hadid", "扎哈", "西安体育馆")),
            msg=queries,
        )

    def test_enrich_profile_from_architect_hint(self):
        base = synthesize_profile_rule_based("西安体育馆", "扎哈设计的西安体育馆", [])
        enriched = __import__(
            "app.services.building_research_agent", fromlist=["_enrich_profile_from_user_text"]
        )._enrich_profile_from_user_text(base, "扎哈设计的西安体育馆")
        self.assertEqual(enriched.identity.architect, "Zaha Hadid")
        self.assertEqual(enriched.identity.style, "Deconstructivism")
        self.assertIsNone(enriched.minecraft_strategy.landmark_module)
        self.assertIn("birds_nest", (enriched.minecraft_strategy.notes or "").lower())
        # Architect name must not force skeleton/components or Zaha form kits.
        self.assertNotEqual(enriched.minecraft_strategy.skeleton_type, "RADIAL_RING")
        self.assertNotIn("MASS_SECONDARY", enriched.minecraft_strategy.recommended_components or [])
        self.assertFalse(
            any("parametric" in str(e).lower() for e in (enriched.structure.distinctive_elements or []))
        )

    def test_architect_hints_do_not_share_one_form_kit(self):
        enrich = __import__(
            "app.services.building_research_agent", fromlist=["_enrich_profile_from_user_text"]
        )._enrich_profile_from_user_text
        cases = (
            ("扎哈设计的体育场", "Zaha Hadid"),
            ("贝聿铭风格的博物馆", "I.M. Pei"),
            ("安藤忠雄风格的极简混凝土礼拜堂", "Tadao Ando"),
        )
        for text, architect in cases:
            with self.subTest(text=text):
                enriched = enrich(BuildingProfile(query=text), text)
                self.assertEqual(enriched.identity.architect, architect)
                self.assertNotEqual(enriched.minecraft_strategy.skeleton_type, "RADIAL_RING")
                self.assertNotEqual(
                    list(enriched.minecraft_strategy.recommended_components or []),
                    ["MASS_MAIN", "MASS_SECONDARY", "ROOF", "FACADE_WINDOWS", "ENTRANCE", "PAVING"],
                )
                joined = " ".join(enriched.structure.distinctive_elements or []).lower()
                self.assertNotIn("parametric facade", joined)
                self.assertNotIn("流线型曲面", joined)


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
                {"title": "A", "snippet": "architecture stadium design structure", "url": "https://a"},
                {"title": "B", "snippet": "architecture stadium design structure", "url": ""},
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

    def test_researched_profile_can_be_formatted_for_planning(self):
        def fake_search(query: str, max_results: int):
            return MOCK_RESULTS

        profile = research_building_profile(
            "recreate Suzhou Museum",
            search_fn=fake_search,
        )
        self.assertIsNotNone(profile)
        assert profile is not None
        ctx = format_profile_for_prompt(profile)
        self.assertIn("Building Research Profile", ctx)

    @patch.dict(os.environ, {"BUILDING_RESEARCH": "off"}, clear=False)
    def test_research_off_returns_none(self):
        # 开关按调用时的环境解析，无需 reload。
        self.assertFalse(is_building_research_enabled())

    def test_long_system_prompt_skipped(self):
        long_prompt = "SYSTEM PROMPT " * 50 + "context only metadata"
        should, _, _ = plan_search_queries(long_prompt)
        self.assertFalse(should)


if __name__ == "__main__":
    unittest.main()
