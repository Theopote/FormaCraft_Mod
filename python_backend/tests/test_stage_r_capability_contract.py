"""Stage R research prompts must share the capability catalog with Stage P."""

from __future__ import annotations

import unittest

from app.services.building_research_agent import _research_synthesis_system_prompt
from app.services.generation_capability import plannable_component_types


class StageRCapabilityContractTest(unittest.TestCase):
    def test_research_prompt_uses_catalog_not_legacy_ten_type_whitelist(self):
        prompt = _research_synthesis_system_prompt()
        self.assertIn("BRIDGE", prompt)
        self.assertIn("PAVING", prompt)
        self.assertIn("ROOF_STRUCTURE", prompt)
        for token in plannable_component_types()[:8]:
            self.assertIn(token, prompt)
        # Legacy Stage R whitelist / typology-first executor binding must be gone.
        self.assertNotIn(
            "recommended_components must use only: STRUCTURE, MASS_MAIN, ROOF",
            prompt,
        )
        self.assertNotIn("they are typology-first only", prompt)
        self.assertNotIn('recommended_components must be ["STRUCTURE"] only', prompt)
        self.assertIn("does NOT choose fixed whole-building executors", prompt)
        self.assertIn("landmark_module=null", prompt)


if __name__ == "__main__":
    unittest.main()
