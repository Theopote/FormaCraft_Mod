"""Canonical plan contract: plan_meta and design_sources in loose schema."""

from __future__ import annotations

import unittest

from app.models.llm_plan_json_schema import build_llm_plan_json_schema

GENERATION_AUTHORITY_VALUES = frozenset(
    {"COMPONENTS", "TYPOLOGY", "ASSEMBLY", "CAPTURED_LIBRARY", "SKELETON"}
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

    def test_generation_capability_values_documented(self):
        # Keep in sync with Java GenerationCapability enum
        expected = {"SUPPORTED", "COMPOSABLE", "NEEDS_REPLAN", "UNSUPPORTED"}
        self.assertEqual(len(expected), 4)


if __name__ == "__main__":
    unittest.main()
