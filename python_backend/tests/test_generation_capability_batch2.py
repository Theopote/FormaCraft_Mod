"""Batch 2: capability catalog + stage-B repair + temple/gate composition."""

from __future__ import annotations

import unittest

from pathlib import Path

from app.services.generation_capability import (
    apply_stage_b_capability_repair,
    catalog_source_path,
    evaluate_capability,
    is_executable_roof_type,
    load_catalog,
    normalize_roof_type,
)
from app.services.plan_semantic_gate import assert_plan_semantics, evaluate_plan_semantics
from app.services.request_intent import extract_request_intent, scaffold_plan_for_intent, attach_request_intent


class GenerationCapabilityBatch2Test(unittest.TestCase):
    def test_catalog_loads_shared_resource(self):
        catalog = load_catalog(force=True, allow_fallback=False)
        self.assertEqual(catalog["schema"], "formacraft.generation_capability.v1")
        self.assertNotEqual(catalog.get("_fallback"), True)
        source = catalog_source_path()
        self.assertIsNotNone(source)
        self.assertTrue(
            source.replace("\\", "/").endswith(
                "src/main/resources/assets/formacraft/generation_capability_catalog.json"
            )
            or source.replace("\\", "/").endswith(
                "build/resources/main/assets/formacraft/generation_capability_catalog.json"
            ),
            f"expected Java shared catalog, got {source}",
        )
        self.assertTrue(Path(source).is_file())
        self.assertIn("GATE", catalog.get("hostable_component_types") or [])
        # Shared JSON includes tokens the Python-only fallback historically omitted.
        tokens = (catalog.get("roof") or {}).get("supported_tokens") or []
        self.assertIn("mansard", tokens)
        self.assertEqual(normalize_roof_type("irimoya"), "xieshan")
        self.assertTrue(is_executable_roof_type("irimoya"))

    def test_stage_b_maps_irimoya_and_typology_alias(self):
        plan = {
            "mode": "build",
            "components": [
                {
                    "component_type": "STRUCTURE",
                    "params": {"component_id": "tower", "typology": "dense_eaves_pagoda"},
                    "dimensions": {"width": 7, "depth": 7, "height": 21},
                    "features": [],
                },
                {
                    "component_type": "ROOF",
                    "params": {
                        "component_id": "r1",
                        "host_id": "tower",
                        "roof_type": "irimoya",
                    },
                    "dimensions": {"width": 9, "depth": 9, "height": 3},
                },
            ],
        }
        out, errors = apply_stage_b_capability_repair(plan, "日式庭院 入母屋")
        self.assertEqual(out["components"][0]["params"]["typology_id"], "dense_eaves_pagoda")
        self.assertEqual(out["components"][1]["params"]["roof_type"], "xieshan")
        self.assertEqual(out["components"][1]["params"]["roof_type_mapped_from"], "irimoya")
        self.assertFalse(any("unsupported roof_type" in e for e in errors), errors)

    def test_gate_mass_with_passage_passes_semantics(self):
        plan = attach_request_intent(
            {
                "mode": "build",
                "style_profile": "DEFAULT",
                "anchor": {"x": 0, "y": 64, "z": 0},
                "components": [
                    {
                        "component_type": "MASS_MAIN",
                        "slot_id": "gate",
                        "relative_position": {"x": 0, "y": 0, "z": 0},
                        "dimensions": {"width": 14, "depth": 5, "height": 8},
                        "features": ["山门"],
                        "params": {
                            "component_id": "gate_main",
                            "passage_axis": "z",
                            "door_width": 3,
                            "roof_type": "xieshan",
                        },
                    },
                    {
                        "component_type": "ROOF",
                        "slot_id": "gate",
                        "relative_position": {"x": 0, "y": 8, "z": 0},
                        "dimensions": {"width": 16, "depth": 7, "height": 3},
                        "features": [],
                        "params": {
                            "component_id": "gate_roof",
                            "host_id": "gate_main",
                            "roof_type": "xieshan",
                        },
                    },
                ],
            },
            "生成一座中式寺庙山门",
        )
        self.assertEqual([], evaluate_plan_semantics(plan, "生成一座中式寺庙山门"))
        assert_plan_semantics(plan, "生成一座中式寺庙山门")

    def test_plain_temple_is_single_compound_is_site(self):
        single = extract_request_intent("生成一座传统道观")
        self.assertEqual(single["building_class"], "temple")
        self.assertEqual(single["composition"], "single")
        single_plan = scaffold_plan_for_intent(single, anchor={"x": 8, "y": 64, "z": 8})
        types = {c["component_type"] for c in single_plan["components"]}
        self.assertIn("MASS_MAIN", types)
        self.assertNotIn("COURTYARD", types)

        compound = extract_request_intent("生成一座道观建筑群，带厢房院落")
        self.assertEqual(compound["building_class"], "temple")
        self.assertEqual(compound["composition"], "compound")
        compound_plan = scaffold_plan_for_intent(compound, anchor={"x": 8, "y": 64, "z": 8})
        ctypes = {c["component_type"] for c in compound_plan["components"]}
        self.assertTrue({"MASS_MAIN", "MASS_SECONDARY", "ROOF"} <= ctypes)
        peidian = [
            c for c in compound_plan["components"]
            if c.get("component_type") == "MASS_SECONDARY"
            and (c.get("params") or {}).get("building_role") == "side_hall"
        ]
        self.assertGreaterEqual(len(peidian), 2)

    def test_evaluate_flags_unmapped_roof(self):
        plan = {
            "components": [
                {
                    "component_type": "ROOF",
                    "params": {"component_id": "r", "roof_type": "totally_fake_roof_xyz"},
                }
            ]
        }
        errors = evaluate_capability(plan)
        self.assertTrue(any("unsupported roof_type" in e for e in errors), errors)

    def test_parametric_structure_type_does_not_require_typology(self):
        plan = {
            "components": [
                {
                    "component_type": "STRUCTURE",
                    "params": {
                        "component_id": "apse_colonnade",
                        "structure_type": "radial_colonnade",
                        "count": 8,
                    },
                    "features": ["columns:8"],
                    "dimensions": {"width": 16, "depth": 16, "height": 10},
                }
            ]
        }
        errors = evaluate_capability(plan)
        self.assertFalse(any("lacks typology_id" in e for e in errors), errors)

    def test_unknown_structure_type_is_rejected(self):
        plan = {
            "components": [
                {
                    "component_type": "STRUCTURE",
                    "params": {
                        "component_id": "mystery",
                        "structure_type": "unsupported_structure",
                    },
                    "dimensions": {"width": 8, "depth": 8, "height": 6},
                }
            ]
        }
        errors = evaluate_capability(plan)
        self.assertTrue(
            any("unsupported structure_type" in e for e in errors),
            errors,
        )


if __name__ == "__main__":
    unittest.main()
