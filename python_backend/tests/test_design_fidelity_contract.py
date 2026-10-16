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
