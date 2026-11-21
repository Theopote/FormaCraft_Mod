"""System prompt must frame category composition as non-binding examples."""

from __future__ import annotations

import unittest

from app.services.llm_plan_system_prompt import build_llm_plan_system_prompt


class LlmPlanSystemPromptAuthorityTest(unittest.TestCase):
    def test_assembly_stair_guidance_preserves_explicit_tread_choice(self):
        prompt = build_llm_plan_system_prompt(None)
        self.assertIn("Omit stairs to use the matching", prompt)
        self.assertIn("specify stairs for an intentional material or state", prompt)

    def test_composition_bullets_are_nonbinding_examples(self):
        prompt = build_llm_plan_system_prompt(None)
        self.assertIn("NON-BINDING EXAMPLES", prompt)
        self.assertIn("NOT mandatory templates", prompt)
        self.assertIn("explicit user opt-in", prompt)
        self.assertIn("compose from MASS/ROOF/FACADE/ASSEMBLY/FBD", prompt)

    def test_residential_kit_is_not_universal_default(self):
        prompt = build_llm_plan_system_prompt(None)
        self.assertIn("not a default for every building class", prompt)

    def test_linear_detail_guidance_names_executable_fields_without_requiring_variation(self):
        prompt = build_llm_plan_system_prompt(None)
        self.assertIn('optional PRIMITIVE box with params.kind="box"', prompt)
        self.assertIn("Use STRUCTURE only with a supported executable parameter set", prompt)
        self.assertNotIn("Never emit identical plans", prompt)


if __name__ == "__main__":
    unittest.main()
