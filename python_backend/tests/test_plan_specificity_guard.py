"""Specificity guard — skip homogenizing classical enrichment for distinctive buildings."""

import unittest

from app.models.building_profile import BuildingProfile, ProfileForm, ProfileStructure
from app.services.plan_specificity_guard import (
    apply_profile_features_to_plan,
    extract_distinguishing_tokens,
    score_feature_token_coverage,
    should_skip_classical_enrichment,
)


class TestPlanSpecificityGuard(unittest.TestCase):
    def test_extract_tokens_from_profile(self):
        profile = BuildingProfile(
            structure=ProfileStructure(
                distinguishing_features=["white sail shells", "shell roof"],
                distinctive_elements=["waterfront podium"],
            ),
        )
        tokens = extract_distinguishing_tokens(profile)
        self.assertIn("white sail shells", tokens)
        self.assertIn("shell roof", tokens)
        self.assertIn("waterfront podium", tokens)

    def test_should_skip_for_zaha(self):
        profile = BuildingProfile(
            query="扎哈体育场",
            structure=ProfileStructure(
                distinguishing_features=["curved facade", "parametric mesh"],
            ),
        )
        skip, reason = should_skip_classical_enrichment("扎哈风格的体育场", profile)
        self.assertTrue(skip)
        self.assertIn("non_classical_marker", reason)

    def test_should_not_skip_for_louvre(self):
        profile = BuildingProfile(
            query="louvre",
            structure=ProfileStructure(
                distinguishing_features=["mansard roof", "courtyard pyramid"],
            ),
        )
        skip, _ = should_skip_classical_enrichment("louvre museum classical palace", profile)
        self.assertFalse(skip)

    def test_apply_profile_features_injects_into_plan(self):
        profile = BuildingProfile(
            structure=ProfileStructure(
                distinguishing_features=["shell roof", "sail shells"],
            ),
        )
        plan = {
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "features": [],
                    "params": {},
                },
            ],
        }
        out = apply_profile_features_to_plan(plan, profile)
        mass = out["components"][0]
        self.assertIn("shell_roof", mass["features"])
        self.assertIn("shell roof", out["style_attributes"]["decorative_elements"])
        self.assertEqual(
            out["distinguishing_features"],
            ["shell roof", "sail shells"],
        )

    def test_feature_coverage_scoring(self):
        plan = {
            "style_attributes": {
                "decorative_elements": ["white sail shells", "waterfront podium"],
            },
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "features": ["white_sail_shells", "shell_roof"],
                    "params": {"distinctive_features": ["waterfront podium"]},
                },
            ],
        }
        tokens = ["white sail shells", "shell roof", "waterfront podium"]
        coverage, matched, missing = score_feature_token_coverage(plan, tokens)
        self.assertGreaterEqual(coverage, 0.66)
        self.assertIn("white sail shells", matched)
        self.assertIn("waterfront podium", matched)
        self.assertIn("shell roof", matched)
        self.assertEqual(missing, [])


if __name__ == "__main__":
    unittest.main()
