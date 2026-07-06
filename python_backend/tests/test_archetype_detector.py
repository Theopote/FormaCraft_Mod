"""Tiered archetype detection — generic typology must not route MODULE."""

from __future__ import annotations

import unittest

from app.services.archetype_detector import (
    MODULE_ROUTE_MIN_CONFIDENCE,
    detect_archetype_local,
)
from app.models.building_profile import BuildingProfile
from app.services.building_research_agent import resolve_landmark_module_for_intent
from app.services.building_request_classifier import classify_building_request_local
from app.services.research_landmark_seeds import apply_research_landmark_seed
from app.services.landmark_alias_matcher import (
    GENERIC_TYPOLOGY_CONFIDENCE,
    PROPER_NOUN_CONFIDENCE,
    has_specific_remainder,
    is_broad_alias,
    match_archetype_aliases,
)


class LandmarkAliasMatcherTest(unittest.TestCase):
    def test_broad_cathedral_alias(self):
        self.assertTrue(is_broad_alias("大教堂"))
        self.assertTrue(is_broad_alias("cathedral"))

    def test_sagrada_has_specific_remainder_after_cathedral(self):
        intent = "生成圣家族大教堂".lower()
        self.assertTrue(has_specific_remainder(intent, "大教堂"))

    def test_proper_gothic_cathedral_phrase(self):
        result = match_archetype_aliases(
            "哥特大教堂",
            ["哥特大教堂", "notre dame"],
            ["大教堂", "cathedral", "gothic cathedral"],
        )
        self.assertIsNotNone(result)
        self.assertEqual(result.confidence, PROPER_NOUN_CONFIDENCE)
        self.assertIn("proper_noun", result.reason_tags)

    def test_generic_cathedral_only_low_confidence(self):
        result = match_archetype_aliases(
            "生成一个大教堂",
            ["哥特大教堂", "notre dame"],
            ["大教堂", "cathedral"],
        )
        self.assertIsNotNone(result)
        self.assertEqual(result.confidence, GENERIC_TYPOLOGY_CONFIDENCE)
        self.assertIn("generic_typology", result.reason_tags)

    def test_sagrada_rejects_broad_cathedral_match(self):
        result = match_archetype_aliases(
            "生成圣家族大教堂",
            ["哥特大教堂", "notre dame", "cologne cathedral", "chartres"],
            ["gothic cathedral", "哥特", "大教堂", "cathedral"],
        )
        self.assertIsNone(result)


class ArchetypeDetectorTest(unittest.TestCase):
    def test_sagrada_does_not_route_gothic_cathedral(self):
        match = detect_archetype_local("生成圣家族大教堂")
        if match is not None:
            self.assertFalse(match.qualifies_for_module_route())
        self.assertIsNone(resolve_landmark_module_for_intent("生成圣家族大教堂"))

    def test_explicit_gothic_cathedral_detects_archetype_not_module_route(self):
        match = detect_archetype_local("哥特大教堂")
        self.assertIsNotNone(match)
        self.assertEqual(match.id, "gothic_cathedral")
        self.assertGreaterEqual(match.confidence, MODULE_ROUTE_MIN_CONFIDENCE)
        self.assertFalse(match.qualifies_for_module_route())
        self.assertIsNone(resolve_landmark_module_for_intent("哥特大教堂"))

    def test_generic_cathedral_only_typology_hint(self):
        match = detect_archetype_local("生成一个大教堂")
        if match is not None:
            self.assertEqual(match.confidence, GENERIC_TYPOLOGY_CONFIDENCE)
            self.assertFalse(match.qualifies_for_module_route())
        self.assertIsNone(resolve_landmark_module_for_intent("生成一个大教堂"))

    def test_disney_castle_does_not_route_castle_compound(self):
        self.assertIsNone(resolve_landmark_module_for_intent("迪士尼城堡"))

    def test_medieval_castle_routes_module(self):
        self.assertEqual(
            resolve_landmark_module_for_intent("中世纪城堡"),
            "castle_compound",
        )


class ResearchOnlyLandmarkTest(unittest.TestCase):
    def test_notre_dame_does_not_route_gothic_module(self):
        self.assertIsNone(resolve_landmark_module_for_intent("复原巴黎圣母院"))

    def test_notre_dame_is_specific_for_research(self):
        rc = classify_building_request_local("复原巴黎圣母院")
        self.assertTrue(rc.is_specific_real_building)

    def test_cologne_cathedral_research_only(self):
        self.assertIsNone(resolve_landmark_module_for_intent("科隆大教堂"))
        rc = classify_building_request_local("科隆大教堂")
        self.assertTrue(rc.is_specific_real_building)

    def test_gothic_cathedral_template_routes_typology_not_module(self):
        from app.services.keyword_culture_retriever import resolve_landmark_module_routing

        routing = resolve_landmark_module_routing("哥特大教堂")
        self.assertIsNotNone(routing)
        self.assertEqual(routing.get("typologyId"), "gothic_cathedral_hall")
        self.assertEqual(routing.get("componentType"), "STRUCTURE")

    def test_mingqing_courtyard_template_routes_typology_not_module(self):
        from app.services.keyword_culture_retriever import resolve_landmark_module_routing

        routing = resolve_landmark_module_routing("明清官式院落")
        self.assertIsNotNone(routing)
        self.assertEqual(routing.get("typologyId"), "courtyard_compound")
        self.assertEqual(routing.get("componentType"), "STRUCTURE")


class BroadTypologyAliasTest(unittest.TestCase):
    def test_fushimi_inari_not_japanese_shrine_module(self):
        self.assertIsNone(resolve_landmark_module_for_intent("建造伏见稻荷神社"))
        rc = classify_building_request_local("建造伏见稻荷神社")
        self.assertTrue(rc.is_specific_real_building)

    def test_wuzhen_not_jiangnan_module(self):
        self.assertIsNone(resolve_landmark_module_for_intent("建造乌镇"))
        rc = classify_building_request_local("建造乌镇")
        self.assertTrue(rc.is_specific_real_building)

    def test_jiangnan_explicit_still_routes_module(self):
        self.assertEqual(
            resolve_landmark_module_for_intent("江南水乡"),
            "jiangnan_water_town",
        )

    def test_himeji_not_generic_japanese_castle_module(self):
        self.assertIsNone(resolve_landmark_module_for_intent("姬路城"))

    def test_japanese_castle_keep_template_still_routes(self):
        self.assertEqual(
            resolve_landmark_module_for_intent("日本城堡"),
            "japanese_castle_keep",
        )

    def test_generic_shrine_typology_low_confidence(self):
        match = detect_archetype_local("帮我建一个神社")
        if match is not None:
            self.assertFalse(match.qualifies_for_module_route())

    def test_disney_castle_research_not_medieval_module(self):
        self.assertIsNone(resolve_landmark_module_for_intent("迪士尼城堡"))
        updated, lid = apply_research_landmark_seed(BuildingProfile(), "迪士尼城堡")
        self.assertEqual(lid, "disney_castle")
        self.assertTrue(any("fairytale" in f.lower() for f in updated.structure.distinguishing_features))


if __name__ == "__main__":
    unittest.main()
