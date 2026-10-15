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
