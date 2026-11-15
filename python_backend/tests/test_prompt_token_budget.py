"""P1.5: compact prompt blocks — less duplicate routing token spend."""

from __future__ import annotations

import unittest

from app.models.building_profile import BuildingProfile, ProfileMinecraftStrategy
from app.services.building_plan_stage import (
    plan_stage_system_augmentation,
    profile_routing_summary,
)
from app.services.building_research_agent import (
    finalize_profile_minecraft_strategy,
    format_profile_for_prompt,
)


class PromptTokenBudgetTest(unittest.TestCase):
    def _temple_profile(self) -> BuildingProfile:
        return finalize_profile_minecraft_strategy(
            BuildingProfile(
                query="盖一座天坛",
                minecraft_strategy=ProfileMinecraftStrategy(),
            ),
            "盖一座天坛",
        )

    def test_plan_stage_addon_defers_routing_to_override(self):
        addon = plan_stage_system_augmentation()
        self.assertIn("PLAN STAGE", addon)
        self.assertIn("OPEN-WORLD RESEARCH OVERRIDE", addon)
        self.assertNotIn("MODULE + landmark:", addon)
        self.assertNotIn("vertical_pilasters", addon)

    def test_compact_profile_omits_migrated_landmark_enumeration(self):
        profile = self._temple_profile()
        text = format_profile_for_prompt(profile, compact_routing=True)
        self.assertNotIn("famen_pagoda", text)
        self.assertNotIn("gothic_cathedral", text)
        self.assertIn("Model-designed landmark", text)
        self.assertIn(profile_routing_summary(profile), text)
        self.assertNotIn("typology:radial_terrace_hall", text)
        self.assertNotIn("CRITICAL routing (typology-first)", text)

    def test_compact_profile_saves_tokens_vs_legacy(self):
        """Model-designed prompts must not reintroduce legacy typology routing text."""
        profile = self._temple_profile()
        compact = format_profile_for_prompt(profile, compact_routing=True)
        legacy = format_profile_for_prompt(profile, compact_routing=False)
        # Both modes share the model-designed contract; neither should resurrect
        # the long typology-first / landmark-enumeration instructions.
        self.assertEqual(compact, legacy)
        # Heading + routing summary both mention model-designed; no third copy.
        self.assertEqual(compact.count("Model-designed landmark"), 2)
        self.assertEqual(compact.count("Routing summary:"), 1)
        self.assertNotIn("CRITICAL routing (typology-first)", compact)
        self.assertNotIn("AVAILABLE LANDMARK MODULES", compact)
        self.assertNotIn("famen_pagoda", compact)


if __name__ == "__main__":
    unittest.main()
