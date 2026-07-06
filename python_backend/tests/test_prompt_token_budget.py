"""P1.5: compact prompt blocks — less duplicate routing token spend."""

from __future__ import annotations

import unittest

from app.models.building_profile import BuildingProfile, ProfileMinecraftStrategy
from app.services.building_plan_stage import (
    PLAN_STAGE_MARKER,
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
        self.assertIn(profile_routing_summary(profile), text)

    def test_compact_profile_saves_tokens_vs_legacy(self):
        profile = self._temple_profile()
        compact = format_profile_for_prompt(profile, compact_routing=True)
        legacy = format_profile_for_prompt(profile, compact_routing=False)
        saved = len(legacy) - len(compact)
        self.assertGreater(saved, 400, msg=f"saved only {saved} chars")


if __name__ == "__main__":
    unittest.main()
