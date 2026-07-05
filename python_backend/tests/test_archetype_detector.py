"""Tiered archetype detection — generic typology must not route MODULE."""

from __future__ import annotations

import unittest

from app.services.archetype_detector import (
    MODULE_ROUTE_MIN_CONFIDENCE,
    detect_archetype_local,
)
from app.services.building_research_agent import resolve_landmark_module_for_intent
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

    def test_explicit_gothic_cathedral_routes_module(self):
        match = detect_archetype_local("哥特大教堂")
        self.assertIsNotNone(match)
        self.assertEqual(match.id, "gothic_cathedral")
        self.assertGreaterEqual(match.confidence, MODULE_ROUTE_MIN_CONFIDENCE)
        self.assertTrue(match.qualifies_for_module_route())
        self.assertEqual(resolve_landmark_module_for_intent("哥特大教堂"), "gothic_cathedral")

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


if __name__ == "__main__":
    unittest.main()
