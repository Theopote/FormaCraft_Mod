"""P2: Java bridge parity — Python normalize should produce plans Java can consume."""

from __future__ import annotations

import unittest

from app.models.building_profile import BuildingProfile, ProfileStructure
from app.services.plan_specificity_guard import apply_profile_features_to_plan


class JavaBridgeParityTest(unittest.TestCase):
    def test_apply_profile_features_sets_top_level_for_java_bridge(self):
        profile = BuildingProfile(
            structure=ProfileStructure(
                distinguishing_features=["lattice windows", "dougong brackets"],
            ),
        )
        plan = {
            "mode": "build",
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 16, "depth": 12, "height": 8},
                    "features": [],
                    "params": {},
                },
            ],
        }
        out = apply_profile_features_to_plan(plan, profile)
        # Research stays in a side channel — Java fidelity must not rely on auto-copied text.
        self.assertEqual(
            out["research_feature_hints"],
            ["lattice windows", "dougong brackets"],
        )
        self.assertNotIn("distinguishing_features", out)
        mass = out["components"][0]
        self.assertEqual(mass["features"], [])
        self.assertNotIn("distinctive_features", mass["params"])


if __name__ == "__main__":
    unittest.main()
