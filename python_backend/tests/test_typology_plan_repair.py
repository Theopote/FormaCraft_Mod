"""Phase 4: typology plan repair — MODULE landmark → STRUCTURE + typology."""

from __future__ import annotations

import unittest


class TypologyPlanRepairTest(unittest.TestCase):
    def test_famen_module_repaired_to_structure(self):
        from app.services.typology_plan_repair import repair_migrated_landmark_components

        plan = {
            "components": [
                {
                    "component_type": "MODULE",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 10, "depth": 10, "height": 47},
                    "features": ["landmark:famen_pagoda"],
                    "params": {"module_id": "famen_pagoda"},
                }
            ]
        }
        out, count = repair_migrated_landmark_components(plan)
        self.assertEqual(count, 1)
        comp = out["components"][0]
        self.assertEqual(comp["component_type"], "STRUCTURE")
        self.assertIn("typology:dense_eaves_pagoda", comp["features"])
        self.assertEqual(comp["params"]["typology_id"], "dense_eaves_pagoda")
        self.assertEqual(comp["params"]["reference_landmark"], "famen_pagoda")
        self.assertEqual(comp["params"]["footprint"], "octagon")

    def test_dayanta_module_repaired_to_square(self):
        from app.services.typology_plan_repair import repair_migrated_landmark_components

        plan = {
            "components": [
                {
                    "component_type": "MODULE",
                    "features": ["landmark:giant_wild_goose_pagoda"],
                    "params": {"module_id": "giant_wild_goose_pagoda"},
                    "dimensions": {"width": 17, "depth": 17, "height": 41},
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                }
            ]
        }
        out, count = repair_migrated_landmark_components(plan)
        self.assertEqual(count, 1)
        comp = out["components"][0]
        self.assertEqual(comp["params"]["footprint"], "square")
        self.assertEqual(comp["params"]["levels"], 7)

    def test_foguang_module_repaired(self):
        from app.services.typology_plan_repair import repair_migrated_landmark_components

        plan = {
            "components": [
                {
                    "component_type": "MODULE",
                    "features": ["landmark:foguang_temple_hall"],
                    "params": {"module_id": "foguang_temple_hall"},
                    "dimensions": {"width": 21, "depth": 15, "height": 7},
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                }
            ]
        }
        out, count = repair_migrated_landmark_components(plan)
        self.assertEqual(count, 1)
        self.assertEqual(out["components"][0]["params"]["typology_id"], "tailiang_timber_hall")

    def test_birds_nest_module_repaired_to_structure(self):
        from app.services.typology_plan_repair import repair_migrated_landmark_components

        plan = {
            "components": [
                {
                    "component_type": "MODULE",
                    "features": ["landmark:birds_nest_stadium"],
                    "params": {"module_id": "birds_nest_stadium", "meshStructure": True},
                    "dimensions": {"width": 60, "depth": 80, "height": 28},
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                }
            ]
        }
        out, count = repair_migrated_landmark_components(plan)
        self.assertEqual(count, 1)
        comp = out["components"][0]
        self.assertEqual(comp["component_type"], "STRUCTURE")
        self.assertIn("typology:stadium_bowl", comp["features"])
        self.assertEqual(comp["params"]["typology_id"], "stadium_bowl")
        self.assertEqual(comp["params"]["reference_landmark"], "birds_nest_stadium")
        self.assertEqual(comp["params"]["width"], 60)

    def test_golden_gate_module_repaired_to_structure(self):
        from app.services.typology_plan_repair import repair_migrated_landmark_components

        plan = {
            "components": [
                {
                    "component_type": "MODULE",
                    "features": ["landmark:golden_gate_bridge"],
                    "params": {"module_id": "golden_gate_bridge", "span": 120},
                    "dimensions": {"width": 9, "depth": 120, "height": 36},
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                }
            ]
        }
        out, count = repair_migrated_landmark_components(plan)
        self.assertEqual(count, 1)
        comp = out["components"][0]
        self.assertEqual(comp["component_type"], "STRUCTURE")
        self.assertIn("typology:suspension_bridge", comp["features"])
        self.assertEqual(comp["params"]["typology_id"], "suspension_bridge")
        self.assertEqual(comp["params"]["reference_landmark"], "golden_gate_bridge")
        self.assertEqual(comp["params"]["span"], 180)

    def test_gothic_cathedral_module_repaired_to_structure(self):
        from app.services.typology_plan_repair import repair_migrated_landmark_components

        plan = {
            "components": [
                {
                    "component_type": "MODULE",
                    "features": ["landmark:gothic_cathedral"],
                    "params": {"module_id": "gothic_cathedral"},
                    "dimensions": {"width": 25, "depth": 45, "height": 30},
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                }
            ]
        }
        out, count = repair_migrated_landmark_components(plan)
        self.assertEqual(count, 1)
        comp = out["components"][0]
        self.assertEqual(comp["component_type"], "STRUCTURE")
        self.assertIn("typology:gothic_cathedral_hall", comp["features"])
        self.assertEqual(comp["params"]["typology_id"], "gothic_cathedral_hall")
        self.assertEqual(comp["params"]["reference_landmark"], "gothic_cathedral")
        self.assertEqual(comp["params"]["width"], 25)

    def test_mingqing_courtyard_module_repaired_to_structure(self):
        from app.services.typology_plan_repair import repair_migrated_landmark_components

        plan = {
            "components": [
                {
                    "component_type": "MODULE",
                    "features": ["landmark:mingqing_courtyard"],
                    "params": {"module_id": "mingqing_courtyard"},
                    "dimensions": {"width": 32, "depth": 28, "height": 10},
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                }
            ]
        }
        out, count = repair_migrated_landmark_components(plan)
        self.assertEqual(count, 1)
        comp = out["components"][0]
        self.assertEqual(comp["component_type"], "STRUCTURE")
        self.assertIn("typology:courtyard_compound", comp["features"])
        self.assertEqual(comp["params"]["typology_id"], "courtyard_compound")
        self.assertEqual(comp["params"]["reference_landmark"], "mingqing_courtyard")
        self.assertEqual(comp["params"]["width"], 32)
        self.assertTrue(comp["params"]["includePaths"])

    def test_castle_compound_module_repaired_to_structure(self):
        from app.services.typology_plan_repair import repair_migrated_landmark_components

        plan = {
            "components": [
                {
                    "component_type": "MODULE",
                    "features": ["landmark:castle_compound"],
                    "params": {"module_id": "castle_compound"},
                    "dimensions": {"width": 48, "depth": 36, "height": 18},
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                }
            ]
        }
        out, count = repair_migrated_landmark_components(plan)
        self.assertEqual(count, 1)
        comp = out["components"][0]
        self.assertEqual(comp["component_type"], "STRUCTURE")
        self.assertIn("typology:radial_fortress", comp["features"])
        self.assertEqual(comp["params"]["typology_id"], "radial_fortress")
        self.assertEqual(comp["params"]["reference_landmark"], "castle_compound")
        self.assertEqual(comp["params"]["width"], 48)
        self.assertTrue(comp["params"]["moat"])

    def test_non_migrated_module_unchanged(self):
        from app.services.typology_plan_repair import repair_migrated_landmark_components

        plan = {
            "components": [
                {
                    "component_type": "MODULE",
                    "features": ["landmark:office_district"],
                    "params": {"module_id": "office_district"},
                    "dimensions": {"width": 48, "depth": 36, "height": 18},
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                }
            ]
        }
        out, count = repair_migrated_landmark_components(plan)
        self.assertEqual(count, 0)
        self.assertEqual(out["components"][0]["component_type"], "MODULE")

    def test_sanitize_strips_remaining_module_with_typology_profile(self):
        from unittest.mock import Mock

        from app.models.building_profile import BuildingProfile, ProfileMinecraftStrategy
        from app.services.ai_planner import _normalize_llm_plan_output, _sanitize_landmark_modules

        profile = BuildingProfile(
            query="大雁塔",
            minecraft_strategy=ProfileMinecraftStrategy(
                structural_typology="dense_eaves_pagoda",
                reference_landmark="giant_wild_goose_pagoda",
                landmark_module=None,
                skeleton_type="VERTICAL_STACK",
                recommended_components=["STRUCTURE", "MASS_MAIN", "ROOF"],
            ),
        )
        plan = {
            "mode": "build",
            "style_profile": "Chinese_Traditional",
            "genome": {"genomeVersion": "1.0"},
            "layout": {"skeleton_type": "VERTICAL_STACK", "slots": []},
            "components": [
                {
                    "component_type": "MODULE",
                    "features": ["landmark:giant_wild_goose_pagoda"],
                    "params": {"module_id": "giant_wild_goose_pagoda"},
                    "dimensions": {"width": 17, "depth": 17, "height": 41},
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                }
            ],
        }
        _sanitize_landmark_modules(plan, Mock(), profile)
        types = [c["component_type"] for c in plan["components"]]
        self.assertIn("STRUCTURE", types)
        self.assertNotIn("MODULE", types)
        struct = next(c for c in plan["components"] if c["component_type"] == "STRUCTURE")
        self.assertEqual(struct["params"]["footprint"], "square")


    def test_temple_module_repaired(self):
        from app.services.typology_plan_repair import repair_migrated_landmark_components

        plan = {
            "components": [
                {
                    "component_type": "MODULE",
                    "features": ["landmark:temple_of_heaven"],
                    "params": {"module_id": "temple_of_heaven", "baseRadius": 18, "tiers": 3},
                    "dimensions": {"width": 36, "depth": 36, "height": 34},
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                }
            ]
        }
        out, count = repair_migrated_landmark_components(plan)
        self.assertEqual(count, 1)
        self.assertEqual(out["components"][0]["params"]["typology_id"], "radial_terrace_hall")
        self.assertEqual(out["components"][0]["params"]["baseRadius"], 18)


if __name__ == "__main__":
    unittest.main()
