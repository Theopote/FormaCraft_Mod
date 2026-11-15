"""Stage R research prompts must share the capability catalog with Stage P."""

from __future__ import annotations

import os
import unittest
from pathlib import Path
from unittest.mock import patch

from app.services import generation_capability as gc
from app.services.building_research_agent import _research_synthesis_system_prompt
from app.services.generation_capability import (
    CapabilityCatalogUnavailable,
    plannable_component_types,
)


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

    def test_strict_mode_research_prompt_does_not_swallow_catalog_errors(self):
        env = dict(os.environ)
        catalog = gc._CATALOG
        source = gc._CATALOG_SOURCE
        paths = gc._catalog_paths
        try:
            os.environ["FORMACRAFT_CAPABILITY_MODE"] = "strict"
            gc._catalog_paths = lambda: [Path("definitely-missing-catalog.json")]
            gc._CATALOG = None
            gc._CATALOG_SOURCE = None
            with self.assertRaises(CapabilityCatalogUnavailable):
                _research_synthesis_system_prompt()
        finally:
            os.environ.clear()
            os.environ.update(env)
            gc._CATALOG = catalog
            gc._CATALOG_SOURCE = source
            gc._catalog_paths = paths


class SydneyOperaSkeletonContractTest(unittest.TestCase):
    def test_sydney_opera_hint_uses_supported_compound_skeleton(self):
        from app.models.building_profile import BuildingProfile
        from app.services.building_research_agent import finalize_profile_minecraft_strategy
        from app.services.llm_plan_layout_normalization import _coerce_skeleton_type

        profile = finalize_profile_minecraft_strategy(
            BuildingProfile(query="悉尼歌剧院"),
            "悉尼歌剧院",
        )
        self.assertEqual(profile.minecraft_strategy.skeleton_type, "COMPOUND")
        self.assertNotEqual(profile.minecraft_strategy.skeleton_type, "CLUSTER")
        self.assertEqual(_coerce_skeleton_type("CLUSTER"), "COMPOUND")


if __name__ == "__main__":
    unittest.main()
