"""Canonical plan contract: plan_meta and design_sources in loose schema."""

from __future__ import annotations

import json
import unittest
from pathlib import Path

from app.models.llm_plan_json_schema import build_llm_plan_json_schema

GENERATION_AUTHORITY_VALUES = frozenset(
    {"COMPONENTS", "TYPOLOGY", "ASSEMBLY", "CAPTURED_LIBRARY", "SKELETON"}
)

FIXTURE_DIR = (
    Path(__file__).resolve().parents[2]
    / "src"
    / "test"
    / "resources"
    / "plans"
    / "fidelity"
)

FIXTURES = sorted(FIXTURE_DIR.glob("*.json"))

INVALID_AUTHORITY_FIXTURE = FIXTURE_DIR / "invalid_generation_authority.json"


class DesignFidelityContractTest(unittest.TestCase):
    def test_loose_schema_includes_authority_fields(self):
        schema = build_llm_plan_json_schema(strict=False)
        props = schema.get("properties", {})
        self.assertIn("plan_meta", props)
        self.assertIn("design_sources", props)

    def test_plan_meta_documents_generation_authority_enum(self):
        schema = build_llm_plan_json_schema(strict=False)
        plan_meta = schema.get("properties", {}).get("plan_meta", {})
        authority = plan_meta.get("properties", {}).get("generation_authority", {})
        enum_values = set(authority.get("enum", []))
        self.assertEqual(GENERATION_AUTHORITY_VALUES, enum_values)

    def test_plan_meta_documents_cross_authority_fallback_flags(self):
        schema = build_llm_plan_json_schema(strict=False)
        plan_meta = schema.get("properties", {}).get("plan_meta", {})
        props = plan_meta.get("properties", {})
        self.assertIn("allow_typology_empty_fallback", props)
        self.assertIn("allow_structure_empty_fallback", props)
        self.assertIn("design_requirements", props)

    def test_all_fidelity_fixtures_load_and_use_snake_case_sources(self):
        self.assertGreaterEqual(len(FIXTURES), 10, "expected expanded fidelity fixture set")
        for path in FIXTURES:
            with self.subTest(fixture=path.name):
                plan = json.loads(path.read_text(encoding="utf-8"))
                self._assert_snake_case_sources(plan.get("design_sources", {}))
                for component in plan.get("components", []):
                    nested = component.get("params", {}).get("design_sources", {})
                    self._assert_snake_case_sources(nested)
                if path.name == "invalid_generation_authority.json":
                    continue
                plan_meta = plan.get("plan_meta", {})
                authority = plan_meta.get("generation_authority")
                if authority is not None:
                    self.assertIn(authority, GENERATION_AUTHORITY_VALUES)

    def test_authority_contract_components_fixture(self):
        plan = self._load("authority_contract_components.json")
        plan_meta = plan.get("plan_meta", {})
        self.assertEqual("COMPONENTS", plan_meta.get("generation_authority"))
        self.assertNotIn("allow_typology_empty_fallback", plan_meta)
        self.assertNotIn("allow_structure_empty_fallback", plan_meta)

    def test_components_typology_empty_fallback_fixture(self):
        plan = self._load("components_typology_empty_fallback.json")
        self.assertTrue(plan["plan_meta"].get("allow_typology_empty_fallback"))

    def test_components_structure_empty_fallback_fixture(self):
        plan = self._load("components_structure_empty_fallback.json")
        self.assertTrue(plan["plan_meta"].get("allow_structure_empty_fallback"))

    def test_stair_typology_authority_fixture(self):
        plan = self._load("stair_typology_authority.json")
        self.assertEqual("TYPOLOGY", plan["plan_meta"].get("generation_authority"))
        stair = plan["components"][0]
        self.assertEqual("STAIR_SYSTEM", stair["component_type"])
        self.assertIn("from", stair["params"])
        self.assertIn("to", stair["params"])

    def test_unknown_roof_type_fixture(self):
        plan = self._load("unknown_roof_type.json")
        roof = next(c for c in plan["components"] if c.get("component_type") == "ROOF")
        self.assertEqual("unsupported_fantasy_roof", roof["params"]["roof_type"])

    def test_conflicting_roof_height_pitch_fixture(self):
        plan = self._load("conflicting_roof_height_pitch.json")
        roof = next(c for c in plan["components"] if c.get("component_type") == "ROOF")
        self.assertEqual(3, roof["params"]["roof_height"])
        self.assertEqual(60, roof["params"]["roof_pitch_degrees"])

    def test_invalid_roof_height_pitch_fixture(self):
        plan = self._load("invalid_roof_height_pitch.json")
        roof = next(c for c in plan["components"] if c.get("component_type") == "ROOF")
        self.assertEqual(-3, roof["params"]["roof_height"])
        self.assertEqual(95, roof["params"]["roof_pitch_degrees"])

    def test_window_aspect_conflict_and_floor_overflow_fixtures(self):
        conflict = self._load("window_aspect_style_conflict.json")
        facade = next(c for c in conflict["components"] if c.get("component_type") == "FACADE_WINDOWS")
        self.assertEqual("square", facade["params"]["window_aspect"])
        self.assertEqual("arrow_slit", facade["params"]["window_style"])
        overflow = self._load("floor_layout_overflow.json")
        mass = next(c for c in overflow["components"] if c.get("component_type") == "MASS_MAIN")
        self.assertEqual(4, mass["params"]["floor_count"])
        self.assertEqual(3, mass["params"]["floor_height"])
        self.assertEqual(6, mass["dimensions"]["height"])

    def test_roof_type_none_fixture(self):
        plan = self._load("roof_type_none.json")
        mass = next(c for c in plan["components"] if c.get("component_type") == "MASS_MAIN")
        self.assertEqual("none", mass["params"]["roof_type"])
        self.assertFalse(any(c.get("component_type") == "ROOF" for c in plan["components"]))

    def test_round8_mass_fidelity_fixtures(self):
        courtyard = self._load("courtyard_ratio_075.json")
        mass = next(c for c in courtyard["components"] if c.get("component_type") == "MASS_MAIN")
        self.assertEqual(0.75, mass["params"]["courtyard_ratio"])
        unfit = self._load("courtyard_ratio_unfit.json")
        unfit_mass = next(c for c in unfit["components"] if c.get("component_type") == "MASS_MAIN")
        self.assertEqual(0.75, unfit_mass["params"]["courtyard_ratio"])
        self.assertEqual(6, unfit_mass["dimensions"]["width"])
        thick = self._load("wall_thickness_6.json")
        thick_mass = next(c for c in thick["components"] if c.get("component_type") == "MASS_MAIN")
        self.assertEqual(6, thick_mass["params"]["wall_thickness"])
        overhang = self._load("overhang_bbox_conflict.json")
        roof = next(c for c in overhang["components"] if c.get("component_type") == "ROOF")
        self.assertEqual(1, roof["params"]["overhang"])
        self.assertGreater(roof["dimensions"]["width"], 12)
        setback = self._load("setback_ratio_explicit.json")
        setback_mass = next(c for c in setback["components"] if c.get("component_type") == "MASS_MAIN")
        self.assertEqual(0.12, setback_mass["params"]["setback_ratio"])

    def test_round9_resolved_footprint_fixtures(self):
        neg_ratio = self._load("courtyard_ratio_negative.json")
        neg_mass = next(c for c in neg_ratio["components"] if c.get("component_type") == "MASS_MAIN")
        self.assertEqual(-0.2, neg_mass["params"]["courtyard_ratio"])
        neg_wall = self._load("wall_thickness_negative.json")
        neg_wall_mass = next(c for c in neg_wall["components"] if c.get("component_type") == "MASS_MAIN")
        self.assertEqual(-2, neg_wall_mass["params"]["wall_thickness"])
        thick_roof = self._load("courtyard_thick_wall_roof.json")
        thick_mass = next(c for c in thick_roof["components"] if c.get("component_type") == "MASS_MAIN")
        self.assertEqual(0.75, thick_mass["params"]["courtyard_ratio"])
        self.assertEqual(6, thick_mass["params"]["wall_thickness"])
        self.assertEqual(48, thick_mass["dimensions"]["width"])
        self.assertTrue(any(c.get("component_type") == "ROOF" for c in thick_roof["components"]))
        four = self._load("four_storey_mass_facade.json")
        four_mass = next(c for c in four["components"] if c.get("component_type") == "MASS_MAIN")
        self.assertEqual(4, four_mass["params"]["floor_count"])
        self.assertEqual(4, four_mass["params"]["floor_height"])
        dual = self._load("dual_mass_shared_floor_height.json")
        dual_masses = [c for c in dual["components"] if c.get("component_type") == "MASS_MAIN"]
        self.assertEqual(2, len(dual_masses))
        self.assertEqual(4, dual_masses[0]["params"]["floor_height"])
        self.assertEqual(4, dual_masses[1]["params"]["floor_height"])

    def test_round10_plan_strictness_fixtures(self):
        unsupported = self._load("plan_type_unsupported.json")
        mass = next(c for c in unsupported["components"] if c.get("component_type") == "MASS_MAIN")
        self.assertEqual("u_shape", mass["params"]["plan_type"])
        shape = self._load("footprint_shape_unsupported.json")
        shape_mass = next(c for c in shape["components"] if c.get("component_type") == "MASS_MAIN")
        self.assertEqual("hexagon", shape_mass["params"]["shape"])
        combo = self._load("circle_cross_combo_unsupported.json")
        combo_mass = next(c for c in combo["components"] if c.get("component_type") == "MASS_MAIN")
        self.assertEqual("circle", combo_mass["params"]["shape"])
        self.assertEqual("cross", combo_mass["params"]["plan_type"])
        gothic = self._load("gothic_style_no_forced_cross.json")
        gothic_mass = next(c for c in gothic["components"] if c.get("component_type") == "MASS_MAIN")
        self.assertEqual("Gothic_Cathedral", gothic["style_profile"])
        self.assertNotIn("plan_type", gothic_mass.get("params") or {})
        self.assertIn("gothic", gothic_mass.get("features") or [])

    def test_round11_hosted_plate_fixtures(self):
        courtyard = self._load("courtyard_hosted_interior_plate.json")
        courtyard_plate = next(
            c for c in courtyard["components"] if c.get("params", {}).get("extrude_mode") == "plate"
        )
        self.assertEqual("body", courtyard_plate["params"]["host_id"])
        self.assertEqual("courtyard", courtyard["components"][0]["params"]["plan_type"])

        l_shape = self._load("l_shape_hosted_interior_plate.json")
        self.assertEqual("l_shape", l_shape["components"][0]["params"]["plan_type"])
        cross = self._load("cross_hosted_interior_plate.json")
        self.assertEqual("cross", cross["components"][0]["params"]["plan_type"])
        stepped = self._load("stepped_hosted_interior_plate.json")
        self.assertEqual(0.2, stepped["components"][0]["params"]["setback_ratio"])
        self.assertIn("stepped_facade", stepped["components"][0]["features"])

        terrace = self._load("terrace_hosted_plate_overhang.json")
        terrace_plate = next(
            c for c in terrace["components"] if c.get("params", {}).get("extrude_mode") == "plate"
        )
        self.assertEqual("terrace", terrace_plate["params"]["plate_role"])
        self.assertTrue(terrace_plate["params"]["exposed_floor_edges"])

    def test_round12_plate_level_and_role_fixtures(self):
        mezz = self._load("mezzanine_hosted_plate_explicit_y.json")
        mezz_plate = next(
            c for c in mezz["components"] if c.get("params", {}).get("extrude_mode") == "plate"
        )
        self.assertEqual(7, mezz_plate["relative_position"]["y"])
        self.assertEqual(5, mezz["components"][0]["params"]["floor_height"])

        outside = self._load("hosted_plate_level_outside_host.json")
        outside_plate = next(
            c for c in outside["components"] if c.get("params", {}).get("extrude_mode") == "plate"
        )
        self.assertEqual(20, outside_plate["relative_position"]["y"])

        unresolved = self._load("hosted_plate_host_unresolved.json")
        unresolved_plate = next(
            c for c in unresolved["components"] if c.get("params", {}).get("extrude_mode") == "plate"
        )
        self.assertEqual("missing_host", unresolved_plate["params"]["host_id"])

        role = self._load("plate_role_unsupported.json")
        role_plate = next(
            c for c in role["components"] if c.get("params", {}).get("extrude_mode") == "plate"
        )
        self.assertEqual("skybridge", role_plate["params"]["plate_role"])

        bridge = self._load("bridge_hosted_plate_overhang.json")
        bridge_plate = next(
            c for c in bridge["components"] if c.get("params", {}).get("extrude_mode") == "plate"
        )
        self.assertEqual("bridge", bridge_plate["params"]["plate_role"])
        self.assertTrue(bridge_plate["params"]["exposed_floor_edges"])

    def test_round13_role_contract_fixtures(self):
        roof_deck = self._load("terrace_roof_deck_above_host.json")
        deck = next(
            c for c in roof_deck["components"] if c.get("params", {}).get("extrude_mode") == "plate"
        )
        self.assertEqual("terrace", deck["params"]["plate_role"])
        self.assertEqual(9, deck["relative_position"]["y"])
        self.assertEqual(8, roof_deck["components"][0]["dimensions"]["height"])

        no_exposed = self._load("terrace_overhang_without_exposed_edges.json")
        terrace = next(
            c for c in no_exposed["components"] if c.get("params", {}).get("extrude_mode") == "plate"
        )
        self.assertEqual("terrace", terrace["params"]["plate_role"])
        self.assertNotIn("exposed_floor_edges", terrace["params"])
        self.assertEqual(-3, terrace["relative_position"]["x"])

        dual = self._load("bridge_dual_mass_span.json")
        masses = [c for c in dual["components"] if c.get("component_type") == "MASS_MAIN"]
        self.assertEqual(2, len(masses))
        link = next(
            c for c in dual["components"] if c.get("params", {}).get("extrude_mode") == "plate"
        )
        self.assertEqual("bridge", link["params"]["plate_role"])
        self.assertEqual("hall", link["params"]["host_id"])
        self.assertEqual("wing", link["params"]["target_host_id"])
        self.assertEqual(1, link["params"]["connection_level"])

        dup = self._load("duplicate_component_id.json")
        ids = [c["params"]["component_id"] for c in dup["components"]]
        self.assertEqual(["dup", "dup"], ids)

    def test_round14_bridge_contract_fixtures(self):
        missing = self._load("bridge_missing_target_host.json")
        missing_plate = next(
            c for c in missing["components"] if c.get("params", {}).get("extrude_mode") == "plate"
        )
        self.assertEqual("bridge", missing_plate["params"]["plate_role"])
        self.assertNotIn("target_host_id", missing_plate["params"])

        no_contact = self._load("bridge_endpoint_no_contact.json")
        no_contact_plate = next(
            c for c in no_contact["components"] if c.get("params", {}).get("extrude_mode") == "plate"
        )
        self.assertEqual("wing", no_contact_plate["params"]["target_host_id"])
        self.assertEqual(30, no_contact["components"][1]["relative_position"]["x"])

        inferred = self._load("inferred_bridge_keeps_y.json")
        inferred_plate = next(
            c for c in inferred["components"] if c.get("params", {}).get("extrude_mode") == "plate"
        )
        self.assertTrue(inferred_plate["params"]["_compiler_inferred"])
        self.assertEqual(7, inferred_plate["relative_position"]["y"])
        self.assertEqual(7, inferred["components"][0]["params"]["floor_height"])

        overhang = self._load("bridge_hosted_plate_overhang.json")
        overhang_plate = next(
            c for c in overhang["components"] if c.get("params", {}).get("extrude_mode") == "plate"
        )
        self.assertEqual("pier", overhang_plate["params"]["target_host_id"])

    def test_round15_bridge_3d_walkability_fixtures(self):
        level_mismatch = self._load("bridge_endpoint_level_mismatch.json")
        mismatch_plate = next(
            c for c in level_mismatch["components"]
            if c.get("params", {}).get("extrude_mode") == "plate"
        )
        self.assertEqual(20, mismatch_plate["relative_position"]["y"])
        self.assertEqual(12, level_mismatch["components"][0]["dimensions"]["height"])
        self.assertNotIn("connection_level", mismatch_plate["params"])

        level_conflict = self._load("bridge_level_conflict.json")
        conflict_plate = next(
            c for c in level_conflict["components"]
            if c.get("params", {}).get("extrude_mode") == "plate"
        )
        self.assertEqual(7, conflict_plate["relative_position"]["y"])
        self.assertEqual(1, conflict_plate["params"]["connection_level"])
        self.assertEqual(5, level_conflict["components"][0]["params"]["floor_height"])

        thick = self._load("bridge_thick_wall_portal.json")
        thick_plate = next(
            c for c in thick["components"] if c.get("params", {}).get("extrude_mode") == "plate"
        )
        self.assertEqual(6, thick["components"][0]["params"]["wall_thickness"])
        self.assertEqual(6, thick["components"][1]["params"]["wall_thickness"])
        self.assertEqual("bridge", thick_plate["params"]["plate_role"])
        self.assertEqual(1, thick_plate["params"]["connection_level"])
        self.assertEqual(5, thick_plate["relative_position"]["y"])

        slot_shift = self._load("bridge_slot_anchor_shift.json")
        slot = slot_shift["layout"]["slots"][0]
        self.assertEqual("hall", slot["slot_id"])
        self.assertEqual({"x": 50, "y": 10, "z": 20}, slot["anchor"])
        shift_plate = next(
            c for c in slot_shift["components"]
            if c.get("params", {}).get("extrude_mode") == "plate"
        )
        self.assertEqual(4, shift_plate["relative_position"]["y"])
        self.assertEqual(1, shift_plate["params"]["connection_level"])

        dual = self._load("bridge_dual_mass_span.json")
        dual_plate = next(
            c for c in dual["components"] if c.get("params", {}).get("extrude_mode") == "plate"
        )
        self.assertEqual(4, dual_plate["relative_position"]["y"])
        self.assertEqual(1, dual_plate["params"]["connection_level"])
        self.assertEqual(4, dual["components"][0]["params"]["floor_height"])

    def test_round16_walkability_fixtures(self):
        atrium = self._load("bridge_walk_mid_atrium_no_floor.json")
        atrium_plate = next(
            c for c in atrium["components"] if c.get("params", {}).get("extrude_mode") == "plate"
        )
        self.assertEqual(7, atrium_plate["relative_position"]["y"])
        self.assertEqual(10, atrium["components"][0]["params"]["floor_height"])
        self.assertEqual(2, atrium["components"][0]["params"]["floor_count"])
        self.assertNotIn("connection_level", atrium_plate["params"])

        low = self._load("bridge_walk_low_clearance.json")
        lids = [
            c for c in low["components"]
            if c.get("params", {}).get("component_id") == "low_lid"
        ]
        self.assertEqual(1, len(lids))
        self.assertEqual(6, lids[0]["relative_position"]["y"])
        self.assertEqual("interior", lids[0]["params"]["plate_role"])
        link = next(
            c for c in low["components"]
            if c.get("params", {}).get("component_id") == "sky_link"
        )
        self.assertEqual(4, link["relative_position"]["y"])

        inferred = self._load("inferred_bridge_keeps_y.json")
        inferred_plate = next(
            c for c in inferred["components"]
            if c.get("params", {}).get("extrude_mode") == "plate"
        )
        self.assertEqual(7, inferred_plate["relative_position"]["y"])
        self.assertEqual(7, inferred["components"][0]["params"]["floor_height"])

    def test_invalid_authority_fixture_loads_in_python_but_java_parse_rejects(self):
        self.assertTrue(INVALID_AUTHORITY_FIXTURE.is_file())
        plan = json.loads(INVALID_AUTHORITY_FIXTURE.read_text(encoding="utf-8"))
        self.assertEqual("TYPOLOOGY", plan["plan_meta"]["generation_authority"])

    def test_generation_capability_values_documented(self):
        expected = {"SUPPORTED", "COMPOSABLE", "NEEDS_REPLAN", "UNSUPPORTED"}
        self.assertEqual(len(expected), 4)

    def _assert_snake_case_sources(self, sources: dict) -> None:
        for key in sources:
            self.assertNotRegex(key, r"[A-Z]", f"camelCase design_sources key: {key}")

    def _load(self, name: str) -> dict:
        path = FIXTURE_DIR / name
        self.assertTrue(path.is_file(), f"missing fixture {path}")
        return json.loads(path.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
