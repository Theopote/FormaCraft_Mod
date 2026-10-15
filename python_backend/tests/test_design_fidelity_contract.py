"""Canonical plan contract: plan_meta and design_sources in loose schema."""

from __future__ import annotations

import json
import unittest
from pathlib import Path

from app.models.llm_plan_json_schema import build_llm_plan_json_schema

GENERATION_AUTHORITY_VALUES = frozenset(
    {"COMPONENTS", "TYPOLOGY", "ASSEMBLY", "CAPTURED_LIBRARY", "SKELETON"}
)

FIXTURE_PATH = (
    Path(__file__).resolve().parents[2]
    / "src"
    / "test"
    / "resources"
    / "plans"
    / "fidelity"
    / "authority_contract_components.json"
)


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

    def test_shared_authority_contract_fixture(self):
        self.assertTrue(FIXTURE_PATH.is_file(), f"missing fixture {FIXTURE_PATH}")
        plan = json.loads(FIXTURE_PATH.read_text(encoding="utf-8"))
        plan_meta = plan.get("plan_meta", {})
        self.assertEqual("COMPONENTS", plan_meta.get("generation_authority"))
        self.assertNotIn("allow_typology_empty_fallback", plan_meta)
        self.assertNotIn("allow_structure_empty_fallback", plan_meta)

        design_sources = plan.get("design_sources", {})
        self.assertIn("window_ratio", design_sources)
        self.assertNotIn("windowRatio", design_sources)

        facade = next(
            c for c in plan.get("components", []) if c.get("component_type") == "FACADE_WINDOWS"
        )
        nested_sources = facade.get("params", {}).get("design_sources", {})
        self.assertIn("window_ratio", nested_sources)
        self.assertNotIn("windowRatio", nested_sources)

    def test_generation_capability_values_documented(self):
        # Keep in sync with Java GenerationCapability enum
        expected = {"SUPPORTED", "COMPOSABLE", "NEEDS_REPLAN", "UNSUPPORTED"}
        self.assertEqual(len(expected), 4)


if __name__ == "__main__":
    unittest.main()
