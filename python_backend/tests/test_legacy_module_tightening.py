"""Phase 6: legacy MODULE tightening for migrated landmarks."""

from __future__ import annotations

import unittest
from unittest.mock import Mock


class LegacyModuleTighteningTest(unittest.TestCase):
    MIGRATED = {
        "famen_pagoda",
        "foguang_temple_hall",
        "giant_wild_goose_pagoda",
        "temple_of_heaven",
        "birds_nest_stadium",
        "golden_gate_bridge",
        "gothic_cathedral",
        "mingqing_courtyard",
        "castle_compound",
        "modern_skyscraper",
    }

    def test_registry_lists_migrated_landmarks(self):
        from app.services.typology_registry import is_migrated_landmark, list_migrated_landmarks

        migrated = set(list_migrated_landmarks())
        self.assertTrue(self.MIGRATED.issubset(migrated))
        for mid in self.MIGRATED:
            self.assertTrue(is_migrated_landmark(mid))
        self.assertFalse(is_migrated_landmark("office_block"))

    def test_gothic_cathedral_routing_returns_typology_not_module(self):
        from app.services.keyword_culture_retriever import resolve_landmark_module_routing

        routing = resolve_landmark_module_routing("生成一座哥特大教堂")
        self.assertIsNotNone(routing)
        self.assertEqual(routing.get("componentType"), "STRUCTURE")
        self.assertEqual(routing.get("typologyId"), "gothic_cathedral_hall")
        self.assertEqual(routing.get("referenceLandmark"), "gothic_cathedral")
        self.assertTrue(routing.get("legacyModuleDeprecated"))
        self.assertNotIn("moduleId", routing)

    def test_mingqing_courtyard_routing_returns_typology_not_module(self):
        from app.services.keyword_culture_retriever import resolve_landmark_module_routing

        routing = resolve_landmark_module_routing("建造一座明清官式院落")
        self.assertIsNotNone(routing)
        self.assertEqual(routing.get("componentType"), "STRUCTURE")
        self.assertEqual(routing.get("typologyId"), "courtyard_compound")
        self.assertEqual(routing.get("referenceLandmark"), "mingqing_courtyard")
        self.assertTrue(routing.get("legacyModuleDeprecated"))
        self.assertNotIn("moduleId", routing)

    def test_castle_compound_routing_returns_typology_not_module(self):
        from app.services.keyword_culture_retriever import resolve_landmark_module_routing

        routing = resolve_landmark_module_routing("建造一座中世纪城堡")
        self.assertIsNotNone(routing)
        self.assertEqual(routing.get("componentType"), "STRUCTURE")
        self.assertEqual(routing.get("typologyId"), "radial_fortress")
        self.assertEqual(routing.get("referenceLandmark"), "castle_compound")
        self.assertTrue(routing.get("legacyModuleDeprecated"))
        self.assertNotIn("moduleId", routing)

    def test_modern_skyscraper_routing_returns_typology_not_module(self):
        from app.services.keyword_culture_retriever import resolve_landmark_module_routing

        routing = resolve_landmark_module_routing("建造一座摩天楼")
        self.assertIsNotNone(routing)
        self.assertEqual(routing.get("componentType"), "STRUCTURE")
        self.assertEqual(routing.get("typologyId"), "setback_tower")
        self.assertEqual(routing.get("referenceLandmark"), "modern_skyscraper")
        self.assertTrue(routing.get("legacyModuleDeprecated"))
        self.assertNotIn("moduleId", routing)

    def test_golden_gate_routing_returns_typology_not_module(self):
        from app.services.keyword_culture_retriever import resolve_landmark_module_routing

        routing = resolve_landmark_module_routing("生成旧金山金门大桥")
        self.assertIsNotNone(routing)
        self.assertEqual(routing.get("componentType"), "STRUCTURE")
        self.assertEqual(routing.get("typologyId"), "suspension_bridge")
        self.assertEqual(routing.get("referenceLandmark"), "golden_gate_bridge")
        self.assertTrue(routing.get("legacyModuleDeprecated"))
        self.assertNotIn("moduleId", routing)

    def test_birds_nest_routing_returns_typology_not_module(self):
        from app.services.keyword_culture_retriever import resolve_landmark_module_routing

        routing = resolve_landmark_module_routing("建一座鸟巢体育馆")
        self.assertIsNotNone(routing)
        self.assertEqual(routing.get("componentType"), "STRUCTURE")
        self.assertEqual(routing.get("typologyId"), "stadium_bowl")
        self.assertEqual(routing.get("referenceLandmark"), "birds_nest_stadium")
        self.assertTrue(routing.get("legacyModuleDeprecated"))
        self.assertNotIn("moduleId", routing)

    def test_temple_routing_returns_typology_not_module(self):
        from app.services.keyword_culture_retriever import resolve_landmark_module_routing

        routing = resolve_landmark_module_routing("盖一座天坛")
        self.assertIsNotNone(routing)
        self.assertEqual(routing.get("componentType"), "STRUCTURE")
        self.assertEqual(routing.get("typologyId"), "radial_terrace_hall")
        self.assertTrue(routing.get("legacyModuleDeprecated"))
        self.assertNotIn("moduleId", routing)

    def test_famen_routing_returns_typology_not_module(self):
        from app.services.keyword_culture_retriever import resolve_landmark_module_routing

        routing = resolve_landmark_module_routing("建造法门寺塔")
        self.assertIsNotNone(routing)
        self.assertEqual(routing.get("componentType"), "STRUCTURE")
        self.assertEqual(routing.get("typologyId"), "dense_eaves_pagoda")
        self.assertEqual(routing.get("referenceLandmark"), "famen_pagoda")

    def test_culture_retrieve_suppresses_migrated_landmark_module_id(self):
        from app.services.keyword_culture_retriever import retrieve

        rag = retrieve("法门寺塔", topK=1, fewShotK=0)
        self.assertIsNone(rag.get("landmarkModuleId"))
        self.assertEqual(
            (rag.get("typology") or {}).get("structuralTypologyId"),
            "dense_eaves_pagoda",
        )

    def test_sanitize_strips_migrated_module_without_profile(self):
        from app.services.ai_planner import _sanitize_landmark_modules

        plan = {
            "components": [
                {
                    "component_type": "MODULE",
                    "features": ["landmark:temple_of_heaven"],
                    "params": {"module_id": "temple_of_heaven"},
                    "dimensions": {"width": 36, "depth": 36, "height": 34},
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                },
                {
                    "component_type": "MASS_MAIN",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 20, "depth": 20, "height": 10},
                },
            ]
        }
        _sanitize_landmark_modules(plan, Mock(), None)
        types = [c["component_type"] for c in plan["components"]]
        self.assertNotIn("MODULE", types)
        self.assertIn("STRUCTURE", types)

    def test_sanitize_does_not_inject_migrated_module(self):
        from app.models.building_profile import BuildingProfile, ProfileMinecraftStrategy
        from app.services.ai_planner import _sanitize_landmark_modules

        profile = BuildingProfile(
            query="天坛",
            minecraft_strategy=ProfileMinecraftStrategy(
                structural_typology="radial_terrace_hall",
                reference_landmark="temple_of_heaven",
                landmark_module="temple_of_heaven",
                skeleton_type="RADIAL_RING",
                recommended_components=["STRUCTURE", "MASS_MAIN", "ROOF"],
            ),
        )
        plan = {
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 20, "depth": 20, "height": 10},
                }
            ]
        }
        _sanitize_landmark_modules(plan, Mock(), profile)
        types = [c["component_type"] for c in plan["components"]]
        self.assertNotIn("MODULE", types)


if __name__ == "__main__":
    unittest.main()
