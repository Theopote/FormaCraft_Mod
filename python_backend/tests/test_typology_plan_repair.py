"""Phase 4: typology plan repair — MODULE landmark → STRUCTURE + typology."""

from __future__ import annotations

import unittest


class TypologyPlanRepairTest(unittest.TestCase):
    def test_hosted_stair_typology_preserves_envelope(self):
        from app.services.typology_plan_repair import strip_typology_exclusive_compositionals
        for host_key in ("host_id", "hostId"):
            plan = {"components": [{"component_type": "MASS_MAIN", "params": {"component_id": "tower_main"}},
                {"component_type": "STRUCTURE", "params": {"typology_id": "spiral_stair_tower", host_key: "tower_main"}},
                {"component_type": "ROOF"}, {"component_type": "FACADE_WINDOWS"}]}
            result, count = strip_typology_exclusive_compositionals(plan)
            self.assertEqual(count, 0)
            self.assertEqual(result, plan)

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

    def test_modern_skyscraper_module_repaired_to_structure(self):
        from app.services.typology_plan_repair import repair_migrated_landmark_components

        plan = {
            "components": [
                {
                    "component_type": "MODULE",
                    "features": ["landmark:modern_skyscraper"],
                    "params": {"module_id": "modern_skyscraper"},
                    "dimensions": {"width": 19, "depth": 19, "height": 64},
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                }
            ]
        }
        out, count = repair_migrated_landmark_components(plan)
        self.assertEqual(count, 1)
        comp = out["components"][0]
        self.assertEqual(comp["component_type"], "STRUCTURE")
        self.assertIn("typology:setback_tower", comp["features"])
        self.assertEqual(comp["params"]["typology_id"], "setback_tower")
        self.assertEqual(comp["params"]["reference_landmark"], "modern_skyscraper")
        self.assertEqual(comp["params"]["width"], 19)
        self.assertEqual(comp["params"]["floors"], 12)

    def test_potala_palace_module_repaired_to_structure(self):
        from app.services.typology_plan_repair import repair_migrated_landmark_components

        plan = {
            "components": [
                {
                    "component_type": "MODULE",
                    "features": ["landmark:potala_palace"],
                    "params": {"module_id": "potala_palace"},
                    "dimensions": {"width": 48, "depth": 40, "height": 40},
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                }
            ]
        }
        out, count = repair_migrated_landmark_components(plan)
        self.assertEqual(count, 1)
        comp = out["components"][0]
        self.assertEqual(comp["component_type"], "STRUCTURE")
        self.assertIn("typology:tiered_mountain_palace", comp["features"])
        self.assertEqual(comp["params"]["typology_id"], "tiered_mountain_palace")
        self.assertEqual(comp["params"]["reference_landmark"], "potala_palace")
        self.assertEqual(comp["params"]["baseWidth"], 48)
        self.assertEqual(comp["params"]["tiers"], 6)

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
        from app.services.llm_plan_normalization import _normalize_llm_plan_output
        from app.services.typology_plan_repair import _sanitize_landmark_modules

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

    def test_typology_structure_strips_compositional_extras(self):
        from app.services.typology_plan_repair import strip_typology_exclusive_compositionals

        plan = {
            "components": [
                {
                    "component_type": "STRUCTURE",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 9, "depth": 60, "height": 30},
                    "features": ["typology:suspension_bridge"],
                    "params": {
                        "typology_id": "suspension_bridge",
                        "reference_landmark": "golden_gate_bridge",
                    },
                },
                {
                    "component_type": "MASS_MAIN",
                    "relative_position": {"x": 0, "y": 20, "z": 0},
                    "dimensions": {"width": 10, "depth": 10, "height": 6},
                    "features": [],
                    "params": {},
                },
                {
                    "component_type": "TOWER",
                    "relative_position": {"x": 0, "y": 0, "z": -25},
                    "dimensions": {"width": 4, "depth": 4, "height": 30},
                    "features": [],
                    "params": {},
                },
                {
                    "component_type": "ROOF",
                    "relative_position": {"x": -1, "y": 6, "z": -1},
                    "dimensions": {"width": 12, "depth": 12, "height": 3},
                    "features": [],
                    "params": {},
                },
            ]
        }
        out, stripped = strip_typology_exclusive_compositionals(plan)
        self.assertEqual(stripped, 3)
        self.assertEqual(len(out["components"]), 1)
        self.assertEqual(out["components"][0]["component_type"], "STRUCTURE")

    def test_mixed_compound_keeps_independent_mansion_beside_stadium(self):
        from app.services.typology_plan_repair import strip_typology_exclusive_compositionals

        plan = {
            "components": [
                {
                    "component_type": "STRUCTURE",
                    "features": ["typology:stadium_bowl"],
                    "params": {
                        "component_id": "arena",
                        "building_id": "stadium",
                        "typology_id": "stadium_bowl",
                    },
                },
                {
                    "component_type": "MASS_MAIN",
                    "params": {
                        "component_id": "mansion",
                        "building_id": "mansion",
                        "style_profile": "Legacy_Classical_Facade",
                    },
                },
                {
                    "component_type": "ROOF",
                    "params": {
                        "component_id": "mansion_roof",
                        "host_id": "mansion",
                        "building_id": "mansion",
                        "roof_type": "mansard",
                    },
                },
                {
                    "component_type": "FACADE_WINDOWS",
                    "params": {
                        "component_id": "mansion_facade",
                        "host_id": "mansion",
                        "building_id": "mansion",
                    },
                },
                {
                    "component_type": "PATH",
                    "params": {},
                },
            ]
        }
        out, stripped = strip_typology_exclusive_compositionals(plan)
        self.assertEqual(stripped, 0)
        types = [c["component_type"] for c in out["components"]]
        self.assertEqual(
            types,
            ["STRUCTURE", "MASS_MAIN", "ROOF", "FACADE_WINDOWS", "PATH"],
        )

    def test_ambiguous_pagoda_shell_without_building_id_is_stripped(self):
        from app.services.typology_plan_repair import strip_typology_exclusive_compositionals

        plan = {
            "components": [
                {
                    "component_type": "STRUCTURE",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 12, "depth": 12, "height": 40},
                    "features": ["typology:dense_eaves_pagoda"],
                    "params": {"component_id": "pagoda", "typology_id": "dense_eaves_pagoda"},
                },
                {
                    "component_type": "MASS_MAIN",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 12, "depth": 12, "height": 40},
                    "features": [],
                    "params": {"component_id": "pagoda_shell"},
                },
            ]
        }
        out, stripped = strip_typology_exclusive_compositionals(plan)
        self.assertEqual(stripped, 1)
        self.assertEqual(len(out["components"]), 1)
        self.assertEqual(out["components"][0]["params"]["component_id"], "pagoda")

    def test_min_corner_adjacent_buildings_without_building_id_kept(self):
        from app.services.typology_plan_repair import strip_typology_exclusive_compositionals

        plan = {
            "components": [
                {
                    "component_type": "STRUCTURE",
                    "slot_id": "pagoda",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 12, "depth": 12, "height": 40},
                    "features": ["typology:dense_eaves_pagoda"],
                    "params": {
                        "component_id": "pagoda",
                        "typology_id": "dense_eaves_pagoda",
                        "anchor_mode": "min_corner",
                    },
                },
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "hall",
                    "relative_position": {"x": 13, "y": 0, "z": 0},
                    "dimensions": {"width": 80, "depth": 40, "height": 18},
                    "features": [],
                    "params": {"component_id": "main_hall", "anchor_mode": "min_corner"},
                },
            ]
        }
        out, stripped = strip_typology_exclusive_compositionals(plan)
        self.assertEqual(stripped, 0)
        self.assertEqual(len(out["components"]), 2)

    def test_distinct_slot_anchors_keep_independent_hall(self):
        from app.services.typology_plan_repair import strip_typology_exclusive_compositionals

        plan = {
            "layout": {
                "slots": [
                    {"slot_id": "pagoda_slot", "anchor": {"x": 0, "y": 0, "z": 0}},
                    {"slot_id": "hall_slot", "anchor": {"x": 100, "y": 0, "z": 0}},
                ]
            },
            "components": [
                {
                    "component_type": "STRUCTURE",
                    "slot_id": "pagoda_slot",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 12, "depth": 12, "height": 40},
                    "features": ["typology:dense_eaves_pagoda"],
                    "params": {"component_id": "pagoda", "typology_id": "dense_eaves_pagoda"},
                },
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "hall_slot",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 40, "depth": 30, "height": 16},
                    "features": [],
                    "params": {"component_id": "main_hall"},
                },
            ],
        }
        out, stripped = strip_typology_exclusive_compositionals(plan)
        self.assertEqual(stripped, 0)
        self.assertEqual(len(out["components"]), 2)

    def test_missing_geometry_does_not_invent_one_by_one_box(self):
        from app.services.typology_plan_repair import strip_typology_exclusive_compositionals

        plan = {
            "components": [
                {
                    "component_type": "STRUCTURE",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 12, "depth": 12, "height": 40},
                    "features": ["typology:dense_eaves_pagoda"],
                    "params": {"component_id": "pagoda", "typology_id": "dense_eaves_pagoda"},
                },
                {
                    "component_type": "MASS_MAIN",
                    "features": [],
                    "params": {"component_id": "pagoda_shell"},
                },
            ]
        }
        out, stripped = strip_typology_exclusive_compositionals(plan)
        self.assertEqual(stripped, 0)
        self.assertEqual(len(out["components"]), 2)
        self.assertEqual(out["capability_gap"]["code"], "E_COMPONENT_OWNERSHIP_AMBIGUOUS")

    def test_sole_incomplete_annex_kept_with_ownership_gap(self):
        from app.services.typology_plan_repair import strip_typology_exclusive_compositionals

        plan = {
            "components": [
                {
                    "component_type": "STRUCTURE",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 12, "depth": 12, "height": 40},
                    "features": ["typology:dense_eaves_pagoda"],
                    "params": {"component_id": "pagoda", "typology_id": "dense_eaves_pagoda"},
                },
                {
                    "component_type": "MASS_MAIN",
                    "features": [],
                    "params": {"component_id": "incomplete_annex"},
                },
            ]
        }
        out, stripped = strip_typology_exclusive_compositionals(plan)
        self.assertEqual(stripped, 0)
        self.assertEqual(len(out["components"]), 2)
        self.assertEqual(out["capability_gap"]["code"], "E_COMPONENT_OWNERSHIP_AMBIGUOUS")

    def test_unresolvable_host_id_does_not_bypass_overlap_strip(self):
        from app.services.typology_plan_repair import strip_typology_exclusive_compositionals

        plan = {
            "components": [
                {
                    "component_type": "STRUCTURE",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 12, "depth": 12, "height": 40},
                    "features": ["typology:dense_eaves_pagoda"],
                    "params": {"component_id": "pagoda", "typology_id": "dense_eaves_pagoda"},
                },
                {
                    "component_type": "MASS_MAIN",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 12, "depth": 12, "height": 40},
                    "features": [],
                    "params": {"component_id": "pagoda_shell", "host_id": "missing_host"},
                },
                {
                    "component_type": "MASS_MAIN",
                    "relative_position": {"x": 100, "y": 0, "z": 0},
                    "dimensions": {"width": 40, "depth": 30, "height": 16},
                    "features": [],
                    "params": {"component_id": "main_hall"},
                },
            ]
        }
        out, stripped = strip_typology_exclusive_compositionals(plan)
        self.assertEqual(stripped, 1)
        ids = [c["params"]["component_id"] for c in out["components"]]
        self.assertEqual(set(ids), {"pagoda", "main_hall"})

    def test_mixed_compound_strips_overlapping_shell_keeps_distant_hall(self):
        from app.services.typology_plan_repair import strip_typology_exclusive_compositionals

        plan = {
            "components": [
                {
                    "component_type": "STRUCTURE",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 12, "depth": 12, "height": 40},
                    "features": ["typology:dense_eaves_pagoda"],
                    "params": {"component_id": "pagoda", "typology_id": "dense_eaves_pagoda"},
                },
                {
                    "component_type": "MASS_MAIN",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 12, "depth": 12, "height": 40},
                    "features": [],
                    "params": {"component_id": "pagoda_shell"},
                },
                {
                    "component_type": "MASS_MAIN",
                    "relative_position": {"x": 100, "y": 0, "z": 0},
                    "dimensions": {"width": 40, "depth": 30, "height": 16},
                    "features": [],
                    "params": {"component_id": "main_hall"},
                },
            ]
        }
        out, stripped = strip_typology_exclusive_compositionals(plan)
        self.assertEqual(stripped, 1)
        ids = [c["params"]["component_id"] for c in out["components"]]
        self.assertEqual(set(ids), {"pagoda", "main_hall"})

    def test_distinct_slots_missing_geometry_not_independent(self):
        from app.services.typology_plan_repair import strip_typology_exclusive_compositionals

        plan = {
            "layout": {
                "slots": [
                    {"slot_id": "tower_main", "anchor": {"x": 0, "y": 0, "z": 0}},
                    {"slot_id": "tower_shell", "anchor": {"x": 0, "y": 0, "z": 0}},
                ]
            },
            "components": [
                {
                    "component_type": "STRUCTURE",
                    "slot_id": "tower_main",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 12, "depth": 12, "height": 40},
                    "features": ["typology:dense_eaves_pagoda"],
                    "params": {"component_id": "pagoda", "typology_id": "dense_eaves_pagoda"},
                },
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "tower_shell",
                    "features": [],
                    "params": {"component_id": "pagoda_shell"},
                },
            ],
        }
        out, stripped = strip_typology_exclusive_compositionals(plan)
        self.assertEqual(stripped, 0)
        self.assertEqual(len(out["components"]), 2)
        self.assertEqual(out["capability_gap"]["code"], "E_COMPONENT_OWNERSHIP_AMBIGUOUS")

    def test_params_slot_id_applies_slot_anchor(self):
        from app.services.typology_plan_repair import strip_typology_exclusive_compositionals

        plan = {
            "layout": {
                "slots": [
                    {"slot_id": "pagoda_slot", "anchor": {"x": 0, "y": 0, "z": 0}},
                    {"slot_id": "east_slot", "anchor": {"x": 100, "y": 0, "z": 0}},
                ]
            },
            "components": [
                {
                    "component_type": "STRUCTURE",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 12, "depth": 12, "height": 40},
                    "features": ["typology:dense_eaves_pagoda"],
                    "params": {
                        "component_id": "pagoda",
                        "typology_id": "dense_eaves_pagoda",
                        "slot_id": "pagoda_slot",
                    },
                },
                {
                    "component_type": "MASS_MAIN",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 40, "depth": 30, "height": 16},
                    "features": [],
                    "params": {"component_id": "east_hall", "slot_id": "east_slot"},
                },
            ],
        }
        out, stripped = strip_typology_exclusive_compositionals(plan)
        self.assertEqual(stripped, 0)
        self.assertEqual(len(out["components"]), 2)

    def test_distinct_building_id_survives_xz_overlap(self):
        from app.services.typology_plan_repair import strip_typology_exclusive_compositionals

        plan = {
            "components": [
                {
                    "component_type": "STRUCTURE",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 60, "depth": 80, "height": 20},
                    "features": ["typology:stadium_bowl"],
                    "params": {
                        "component_id": "arena",
                        "building_id": "stadium_A",
                        "typology_id": "stadium_bowl",
                    },
                },
                {
                    "component_type": "MASS_MAIN",
                    "relative_position": {"x": 20, "y": 12, "z": 0},
                    "dimensions": {"width": 16, "depth": 16, "height": 24},
                    "features": [],
                    "params": {"component_id": "view_tower", "building_id": "view_tower_B"},
                },
            ]
        }
        out, stripped = strip_typology_exclusive_compositionals(plan)
        self.assertEqual(stripped, 0)
        self.assertEqual(len(out["components"]), 2)
        self.assertIsNone(out.get("capability_gap"))

    def test_ambiguous_annex_kept_with_ownership_gap(self):
        from app.services.typology_plan_repair import strip_typology_exclusive_compositionals

        plan = {
            "components": [
                {
                    "component_type": "STRUCTURE",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 12, "depth": 12, "height": 40},
                    "features": ["typology:dense_eaves_pagoda"],
                    "params": {"component_id": "pagoda", "typology_id": "dense_eaves_pagoda"},
                },
                {
                    "component_type": "MASS_MAIN",
                    "relative_position": {"x": 100, "y": 0, "z": 0},
                    "dimensions": {"width": 40, "depth": 30, "height": 16},
                    "features": [],
                    "params": {"component_id": "main_hall"},
                },
                {
                    "component_type": "MASS_MAIN",
                    "features": [],
                    "params": {"component_id": "incomplete_annex"},
                },
            ]
        }
        out, stripped = strip_typology_exclusive_compositionals(plan)
        self.assertEqual(stripped, 0)
        self.assertEqual(len(out["components"]), 3)
        self.assertEqual(out["capability_gap"]["code"], "E_COMPONENT_OWNERSHIP_AMBIGUOUS")
        self.assertIn("incomplete_annex", out["capability_gap"]["details"]["ambiguous_component_ids"])

    def test_same_building_id_exclusive_roots_strip_shell(self):
        from app.services.typology_plan_repair import strip_typology_exclusive_compositionals

        plan = {
            "components": [
                {
                    "component_type": "STRUCTURE",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 20, "depth": 20, "height": 10},
                    "features": ["typology:stadium_bowl"],
                    "params": {
                        "component_id": "bowl_a",
                        "building_id": "stadium",
                        "typology_id": "stadium_bowl",
                    },
                },
                {
                    "component_type": "STRUCTURE",
                    "relative_position": {"x": 30, "y": 0, "z": 0},
                    "dimensions": {"width": 20, "depth": 20, "height": 10},
                    "features": ["typology:stadium_bowl"],
                    "params": {
                        "component_id": "bowl_b",
                        "building_id": "stadium",
                        "typology_id": "stadium_bowl",
                    },
                },
                {
                    "component_type": "MASS_MAIN",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 20, "depth": 20, "height": 10},
                    "features": [],
                    "params": {"component_id": "redundant_shell", "building_id": "stadium"},
                },
            ]
        }
        out, stripped = strip_typology_exclusive_compositionals(plan)
        self.assertEqual(stripped, 1)
        self.assertEqual(len(out["components"]), 2)
        self.assertTrue(all(c["component_type"] == "STRUCTURE" for c in out["components"]))

    def test_authored_width_one_does_not_invent_overlap(self):
        from app.services.typology_plan_repair import strip_typology_exclusive_compositionals

        plan = {
            "components": [
                {
                    "component_type": "STRUCTURE",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 12, "depth": 12, "height": 10},
                    "features": ["typology:stadium_bowl"],
                    "params": {"component_id": "arena", "typology_id": "stadium_bowl"},
                },
                {
                    "component_type": "MASS_MAIN",
                    "relative_position": {"x": 6, "y": 0, "z": 0},
                    "dimensions": {"width": 1, "depth": 1, "height": 4},
                    "features": [],
                    "params": {"component_id": "slim_tower"},
                },
            ]
        }
        out, stripped = strip_typology_exclusive_compositionals(plan)
        self.assertEqual(stripped, 0)
        self.assertEqual(len(out["components"]), 2)

    def test_exclusive_shell_strips_hosted_roof_and_openings_tree(self):
        from app.services.typology_plan_repair import strip_typology_exclusive_compositionals

        plan = {
            "components": [
                {
                    "component_type": "STRUCTURE",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 12, "depth": 12, "height": 40},
                    "features": ["typology:dense_eaves_pagoda"],
                    "params": {"component_id": "pagoda", "typology_id": "dense_eaves_pagoda"},
                },
                {
                    "component_type": "MASS_MAIN",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 12, "depth": 12, "height": 40},
                    "features": [],
                    "params": {"component_id": "pagoda_shell"},
                },
                {
                    "component_type": "ROOF",
                    "relative_position": {"x": 0, "y": 40, "z": 0},
                    "dimensions": {"width": 12, "depth": 12, "height": 4},
                    "features": [],
                    "params": {"component_id": "shell_roof", "host_id": "pagoda_shell"},
                },
                {
                    "component_type": "FACADE_WINDOWS",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 12, "depth": 12, "height": 40},
                    "features": [],
                    "params": {"component_id": "shell_window", "host_id": "pagoda_shell"},
                },
                {
                    "component_type": "ENTRANCE",
                    "relative_position": {"x": 0, "y": 0, "z": -1},
                    "dimensions": {"width": 3, "depth": 2, "height": 3},
                    "features": [],
                    "params": {"component_id": "shell_door", "host_id": "shell_roof"},
                },
            ]
        }
        out, stripped = strip_typology_exclusive_compositionals(plan)
        self.assertEqual(stripped, 4)
        self.assertEqual(len(out["components"]), 1)
        self.assertEqual(out["components"][0]["params"]["component_id"], "pagoda")

    def test_exclusive_shell_strip_keeps_independent_building_satellites(self):
        from app.services.typology_plan_repair import strip_typology_exclusive_compositionals

        plan = {
            "components": [
                {
                    "component_type": "STRUCTURE",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 12, "depth": 12, "height": 40},
                    "features": ["typology:dense_eaves_pagoda"],
                    "params": {"component_id": "pagoda", "typology_id": "dense_eaves_pagoda"},
                },
                {
                    "component_type": "MASS_MAIN",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 12, "depth": 12, "height": 40},
                    "features": [],
                    "params": {"component_id": "pagoda_shell"},
                },
                {
                    "component_type": "ROOF",
                    "relative_position": {"x": 0, "y": 40, "z": 0},
                    "dimensions": {"width": 12, "depth": 12, "height": 4},
                    "features": [],
                    "params": {"component_id": "shell_roof", "host_id": "pagoda_shell"},
                },
                {
                    "component_type": "MASS_MAIN",
                    "relative_position": {"x": 100, "y": 0, "z": 0},
                    "dimensions": {"width": 18, "depth": 28, "height": 12},
                    "features": [],
                    "params": {"component_id": "mansion", "building_id": "mansion"},
                },
                {
                    "component_type": "ROOF",
                    "relative_position": {"x": 100, "y": 12, "z": 0},
                    "dimensions": {"width": 18, "depth": 28, "height": 6},
                    "features": [],
                    "params": {
                        "component_id": "mansion_roof",
                        "host_id": "mansion",
                        "building_id": "mansion",
                    },
                },
            ]
        }
        out, stripped = strip_typology_exclusive_compositionals(plan)
        ids = {c["params"]["component_id"] for c in out["components"]}
        self.assertEqual(stripped, 2)
        self.assertEqual(ids, {"pagoda", "mansion", "mansion_roof"})

    def test_incomplete_exclusive_root_blocks_spatial_independence(self):
        from app.services.typology_plan_repair import strip_typology_exclusive_compositionals

        plan = {
            "components": [
                {
                    "component_type": "STRUCTURE",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 12, "depth": 12, "height": 40},
                    "features": ["typology:dense_eaves_pagoda"],
                    "params": {"component_id": "pagoda_a", "typology_id": "dense_eaves_pagoda"},
                },
                {
                    "component_type": "STRUCTURE",
                    "features": ["typology:dense_eaves_pagoda"],
                    "params": {"component_id": "pagoda_b", "typology_id": "dense_eaves_pagoda"},
                },
                {
                    "component_type": "MASS_MAIN",
                    "relative_position": {"x": 100, "y": 0, "z": 0},
                    "dimensions": {"width": 20, "depth": 20, "height": 12},
                    "features": [],
                    "params": {"component_id": "maybe_shell_of_b"},
                },
            ]
        }
        out, stripped = strip_typology_exclusive_compositionals(plan)
        self.assertEqual(stripped, 0)
        self.assertEqual(len(out["components"]), 3)
        self.assertEqual(out["capability_gap"]["code"], "E_COMPONENT_OWNERSHIP_AMBIGUOUS")
        self.assertIn("maybe_shell_of_b", out["capability_gap"]["details"]["ambiguous_component_ids"])

    def test_host_cycle_is_ambiguous_not_independent(self):
        from app.services.typology_plan_repair import strip_typology_exclusive_compositionals

        plan = {
            "components": [
                {
                    "component_type": "STRUCTURE",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 12, "depth": 12, "height": 40},
                    "features": ["typology:dense_eaves_pagoda"],
                    "params": {"component_id": "pagoda", "typology_id": "dense_eaves_pagoda"},
                },
                {
                    "component_type": "MASS_MAIN",
                    "relative_position": {"x": 100, "y": 0, "z": 0},
                    "dimensions": {"width": 10, "depth": 10, "height": 8},
                    "features": [],
                    "params": {"component_id": "cycle_a", "host_id": "cycle_b"},
                },
                {
                    "component_type": "ROOF",
                    "relative_position": {"x": 100, "y": 8, "z": 0},
                    "dimensions": {"width": 10, "depth": 10, "height": 3},
                    "features": [],
                    "params": {"component_id": "cycle_b", "host_id": "cycle_a"},
                },
            ]
        }
        out, stripped = strip_typology_exclusive_compositionals(plan)
        self.assertEqual(stripped, 0)
        self.assertEqual(out["capability_gap"]["code"], "E_COMPONENT_OWNERSHIP_AMBIGUOUS")

    def test_distinct_building_id_hosted_on_exclusive_shell_is_conflict(self):
        from app.services.typology_plan_repair import strip_typology_exclusive_compositionals

        plan = {
            "components": [
                {
                    "component_type": "STRUCTURE",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 12, "depth": 12, "height": 40},
                    "features": ["typology:dense_eaves_pagoda"],
                    "params": {
                        "component_id": "pagoda",
                        "building_id": "temple_A",
                        "typology_id": "dense_eaves_pagoda",
                    },
                },
                {
                    "component_type": "MASS_MAIN",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 12, "depth": 12, "height": 40},
                    "features": [],
                    "params": {"component_id": "pagoda_shell", "building_id": "temple_A"},
                },
                {
                    "component_type": "MASS_SECONDARY",
                    "relative_position": {"x": 20, "y": 10, "z": 0},
                    "dimensions": {"width": 8, "depth": 8, "height": 4},
                    "features": [],
                    "params": {
                        "component_id": "view_platform",
                        "building_id": "platform_B",
                        "host_id": "pagoda_shell",
                    },
                },
            ]
        }
        out, stripped = strip_typology_exclusive_compositionals(plan)
        ids = {c["params"]["component_id"] for c in out["components"]}
        self.assertEqual(stripped, 1)
        self.assertIn("view_platform", ids)
        self.assertNotIn("pagoda_shell", ids)
        self.assertEqual(out["capability_gap"]["code"], "E_COMPONENT_OWNERSHIP_CONFLICT")

    def test_hosted_nested_structure_and_paving_strip_with_shell(self):
        from app.services.typology_plan_repair import strip_typology_exclusive_compositionals

        plan = {
            "components": [
                {
                    "component_type": "STRUCTURE",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 12, "depth": 12, "height": 40},
                    "features": ["typology:dense_eaves_pagoda"],
                    "params": {"component_id": "pagoda", "typology_id": "dense_eaves_pagoda"},
                },
                {
                    "component_type": "MASS_MAIN",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 12, "depth": 12, "height": 40},
                    "features": [],
                    "params": {"component_id": "pagoda_shell"},
                },
                {
                    "component_type": "STRUCTURE",
                    "relative_position": {"x": 2, "y": 0, "z": 2},
                    "dimensions": {"width": 4, "depth": 4, "height": 10},
                    "features": ["typology:spiral_stair_tower"],
                    "params": {
                        "component_id": "inner_structure",
                        "typology_id": "spiral_stair_tower",
                        "host_id": "pagoda_shell",
                    },
                },
                {
                    "component_type": "PAVING",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 14, "depth": 14, "height": 1},
                    "features": [],
                    "params": {"component_id": "shell_paving", "host_id": "pagoda_shell"},
                },
                {
                    "component_type": "PATH",
                    "relative_position": {"x": 40, "y": 0, "z": 0},
                    "dimensions": {"width": 4, "depth": 20, "height": 1},
                    "features": [],
                    "params": {"component_id": "site_path"},
                },
            ]
        }
        out, stripped = strip_typology_exclusive_compositionals(plan)
        ids = {c["params"]["component_id"] for c in out["components"]}
        self.assertEqual(stripped, 3)
        self.assertEqual(ids, {"pagoda", "site_path"})

    def test_duplicate_satellite_ids_order_independent(self):
        from app.services.typology_plan_repair import strip_typology_exclusive_compositionals

        def plan_with_order(order_keys):
            comps = {
                "pagoda": {
                    "component_type": "STRUCTURE",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 12, "depth": 12, "height": 40},
                    "features": ["typology:dense_eaves_pagoda"],
                    "params": {"component_id": "pagoda", "typology_id": "dense_eaves_pagoda"},
                },
                "shell": {
                    "component_type": "MASS_MAIN",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 12, "depth": 12, "height": 40},
                    "features": [],
                    "params": {"component_id": "pagoda_shell"},
                },
                "roof_a": {
                    "component_type": "ROOF",
                    "relative_position": {"x": 0, "y": 40, "z": 0},
                    "dimensions": {"width": 12, "depth": 12, "height": 4},
                    "features": [],
                    "params": {"component_id": "shared_roof", "host_id": "pagoda_shell"},
                },
                "mansion": {
                    "component_type": "MASS_MAIN",
                    "relative_position": {"x": 100, "y": 0, "z": 0},
                    "dimensions": {"width": 18, "depth": 28, "height": 12},
                    "features": [],
                    "params": {"component_id": "mansion", "building_id": "mansion"},
                },
                "roof_b": {
                    "component_type": "ROOF",
                    "relative_position": {"x": 100, "y": 12, "z": 0},
                    "dimensions": {"width": 18, "depth": 28, "height": 6},
                    "features": [],
                    "params": {
                        "component_id": "shared_roof",
                        "host_id": "mansion",
                        "building_id": "mansion",
                    },
                },
                "chimney": {
                    "component_type": "CROWN",
                    "relative_position": {"x": 100, "y": 18, "z": 0},
                    "dimensions": {"width": 2, "depth": 2, "height": 4},
                    "features": [],
                    "params": {"component_id": "chimney", "host_id": "shared_roof"},
                },
            }
            return {"components": [comps[k] for k in order_keys]}

        order_a = ["pagoda", "shell", "roof_a", "mansion", "roof_b", "chimney"]
        order_b = ["pagoda", "mansion", "roof_b", "shell", "roof_a", "chimney"]
        out_a, _ = strip_typology_exclusive_compositionals(plan_with_order(order_a))
        out_b, _ = strip_typology_exclusive_compositionals(plan_with_order(order_b))
        ids_a = sorted(c["params"]["component_id"] for c in out_a["components"])
        ids_b = sorted(c["params"]["component_id"] for c in out_b["components"])
        self.assertEqual(ids_a, ids_b)
        self.assertIn("chimney", ids_a)
        self.assertEqual(out_b["capability_gap"]["code"], "E_DUPLICATE_COMPONENT_ID")

    def test_typology_exclusive_keeps_paving_peripheral(self):
        from app.services.typology_plan_repair import strip_typology_exclusive_compositionals

        plan = {
            "components": [
                {
                    "component_type": "STRUCTURE",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 32, "depth": 28, "height": 10},
                    "features": ["typology:courtyard_compound"],
                    "params": {"typology_id": "courtyard_compound"},
                },
                {
                    "component_type": "PAVING",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 8, "depth": 8, "height": 1},
                    "features": [],
                    "params": {},
                },
                {
                    "component_type": "MASS_MAIN",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 10, "depth": 10, "height": 8},
                    "features": [],
                    "params": {},
                },
            ]
        }
        out, stripped = strip_typology_exclusive_compositionals(plan)
        self.assertEqual(stripped, 1)
        types = [c["component_type"] for c in out["components"]]
        self.assertEqual(types, ["STRUCTURE", "PAVING"])

    def test_compositional_plan_without_typology_structure_unchanged(self):
        from app.services.typology_plan_repair import strip_typology_exclusive_compositionals

        plan = {
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 12, "depth": 8, "height": 6},
                    "features": [],
                    "params": {},
                },
                {
                    "component_type": "ROOF",
                    "relative_position": {"x": 0, "y": 6, "z": 0},
                    "dimensions": {"width": 12, "depth": 8, "height": 3},
                    "features": [],
                    "params": {},
                },
            ]
        }
        out, stripped = strip_typology_exclusive_compositionals(plan)
        self.assertEqual(stripped, 0)
        self.assertEqual(len(out["components"]), 2)

    def test_golden_gate_module_repair_also_strips_extras(self):
        from app.services.typology_plan_repair import repair_migrated_landmark_components

        plan = {
            "components": [
                {
                    "component_type": "MODULE",
                    "features": ["landmark:golden_gate_bridge"],
                    "params": {"module_id": "golden_gate_bridge"},
                    "dimensions": {"width": 9, "depth": 120, "height": 36},
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                },
                {
                    "component_type": "TOWER",
                    "relative_position": {"x": 0, "y": 0, "z": -25},
                    "dimensions": {"width": 4, "depth": 4, "height": 30},
                    "features": [],
                    "params": {},
                },
            ]
        }
        out, count = repair_migrated_landmark_components(plan)
        self.assertEqual(count, 1)
        self.assertEqual(len(out["components"]), 1)
        self.assertEqual(out["components"][0]["component_type"], "STRUCTURE")


class LandmarkAuthorityTest(unittest.TestCase):
    def test_profile_without_module_removes_module_requests(self):
        from types import SimpleNamespace
        from app.services.typology_plan_repair import _sanitize_landmark_modules

        plan = {"components": [{"component_type": "MODULE", "features": ["landmark:custom"]},
                               {"component_type": "MASS_MAIN"}]}
        profile = SimpleNamespace(minecraft_strategy=SimpleNamespace(landmark_module=None))
        _sanitize_landmark_modules(plan, None, profile)
        self.assertEqual(plan["components"], [{"component_type": "MASS_MAIN"}])

    def test_expected_module_is_deduplicated_and_normalized(self):
        from types import SimpleNamespace
        from app.services.typology_plan_repair import _sanitize_landmark_modules

        plan = {"components": [
            {"component_type": "MODULE", "features": ["landmark:other"]},
            {"component_type": "MODULE", "features": ["detail", "module:custom"], "params": {"size": 4}},
            {"component_type": "MODULE", "params": {"module_id": "custom"}},
            {"component_type": "MASS_MAIN"},
        ]}
        profile = SimpleNamespace(minecraft_strategy=SimpleNamespace(landmark_module="CUSTOM"))
        _sanitize_landmark_modules(plan, None, profile)
        self.assertEqual(plan["components"], [
            {"component_type": "MODULE", "features": ["detail", "landmark:custom"],
             "params": {"size": 4, "module_id": "custom"}},
            {"component_type": "MASS_MAIN"},
        ])


if __name__ == "__main__":
    unittest.main()
