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
        "potala_palace",
    }

    def test_registry_lists_migrated_landmarks(self):
        from app.services.typology_registry import is_migrated_landmark, list_migrated_landmarks

        migrated = set(list_migrated_landmarks())
        self.assertTrue(self.MIGRATED.issubset(migrated))
        for mid in self.MIGRATED:
            self.assertTrue(is_migrated_landmark(mid))
        self.assertFalse(is_migrated_landmark("office_block"))

    def test_gothic_cathedral_name_does_not_select_executor(self):
        from app.services.keyword_culture_retriever import resolve_landmark_module_routing

        routing = resolve_landmark_module_routing("生成一座哥特大教堂")
        self.assertIsNone(routing)

    def test_mingqing_courtyard_name_does_not_select_executor(self):
        from app.services.keyword_culture_retriever import resolve_landmark_module_routing

        routing = resolve_landmark_module_routing("建造一座明清官式院落")
        self.assertIsNone(routing)

    def test_castle_compound_name_does_not_select_executor(self):
        from app.services.keyword_culture_retriever import resolve_landmark_module_routing

        routing = resolve_landmark_module_routing("建造一座中世纪城堡")
        self.assertIsNone(routing)

    def test_modern_skyscraper_name_does_not_select_executor(self):
        from app.services.keyword_culture_retriever import resolve_landmark_module_routing

        routing = resolve_landmark_module_routing("建造一座摩天楼")
        self.assertIsNone(routing)

    def test_golden_gate_name_does_not_select_executor(self):
        from app.services.keyword_culture_retriever import resolve_landmark_module_routing

        routing = resolve_landmark_module_routing("生成旧金山金门大桥")
        self.assertIsNone(routing)

    def test_birds_nest_name_does_not_select_executor(self):
        from app.services.keyword_culture_retriever import resolve_landmark_module_routing

        routing = resolve_landmark_module_routing("建一座鸟巢体育馆")
        self.assertIsNone(routing)

    def test_temple_name_does_not_select_executor(self):
        from app.services.keyword_culture_retriever import resolve_landmark_module_routing

        routing = resolve_landmark_module_routing("盖一座天坛")
        self.assertIsNone(routing)

    def test_famen_name_does_not_select_executor(self):
        from app.services.keyword_culture_retriever import resolve_landmark_module_routing

        routing = resolve_landmark_module_routing("建造法门寺塔")
        self.assertIsNone(routing)

    def test_culture_retrieve_suppresses_migrated_landmark_module_id(self):
        from app.services.keyword_culture_retriever import retrieve

        rag = retrieve("法门寺塔", topK=1, fewShotK=0)
        self.assertIsNone(rag.get("landmarkModuleId"))
        self.assertEqual(
            (rag.get("typology") or {}).get("structuralTypologyId"),
            "dense_eaves_pagoda",
        )

    def test_sanitize_preserves_unrequested_module_for_route_validation(self):
        from app.models.request import BuildRequest, PlayerInfo, Vec3i, WorldContext
        from app.services.typology_plan_repair import _sanitize_landmark_modules

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
        req = BuildRequest(
            player=PlayerInfo(name="t", pos=Vec3i(x=0, y=64, z=0), facing="NORTH"),
            world=WorldContext(dimension="overworld"),
            requestText="盖一座天坛",
            userMessage="盖一座天坛",
        )
        _sanitize_landmark_modules(plan, req)
        types = [c["component_type"] for c in plan["components"]]
        self.assertIn("MODULE", types)
        self.assertNotIn("STRUCTURE", types)

    def test_sanitize_ignores_non_string_request_fields(self):
        from app.services.typology_plan_repair import _sanitize_landmark_modules

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
        # Boundary: non-string / Mock request fields must not raise TypeError.
        _sanitize_landmark_modules(plan, Mock())
        types = [c["component_type"] for c in plan["components"]]
        self.assertIn("MODULE", types)
        self.assertIn("MASS_MAIN", types)


if __name__ == "__main__":
    unittest.main()
