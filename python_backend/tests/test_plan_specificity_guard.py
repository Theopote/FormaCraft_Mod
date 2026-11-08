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

    def test_should_not_skip_chinese_garden_lattice_windows(self):
        """Regression: 花窗 lattice must not trigger non_classical_marker; East-Asian skip is OK."""
        plan = {
            "style_profile": "DEFAULT",
            "style_attributes": {
                "decorative_elements": ["carved_beam", "lattice_window"],
            },
            "genome": {
                "culturalStyle": {
                    "region": "east_asia",
                    "era": "traditional",
                    "keywords": ["garden", "pavilion", "chinese"],
                },
            },
            "components": [
                {
                    "component_type": "FACADE_WINDOWS",
                    "features": [
                        'component_request:{"component_query": {"semantic": {"tags": ["chinese", "lattice", "round"]}}}}',
                    ],
                    "params": {"window_style": "lattice"},
                },
            ],
        }
        skip, reason = should_skip_classical_enrichment("中式园林", None, plan)
        self.assertTrue(skip)
        self.assertEqual(reason, "traditional_east_asian_context")
        self.assertNotIn("lattice", reason)

    def test_should_still_skip_birds_nest_mesh_grid(self):
        skip, reason = should_skip_classical_enrichment("鸟巢国家体育场", None, None)
        self.assertTrue(skip)
        self.assertIn("non_classical_marker", reason)

    def test_chinese_profile_lattice_windows_not_non_classical_token(self):
        profile = BuildingProfile(
            query="中式园林",
            structure=ProfileStructure(
                distinguishing_features=["lattice windows", "dougong brackets"],
            ),
        )
        skip, reason = should_skip_classical_enrichment("中式园林", profile)
        self.assertTrue(skip)
        self.assertEqual(reason, "traditional_east_asian_context")
        self.assertNotIn("lattice", reason)

    def test_east_asian_user_intent_wins_over_classical_plan_pollution(self):
        text = "生成一座重檐歇山顶大雄宝殿，前有月台，左右各一配殿，配殿用悬山顶，主殿必须是重檐歇山。"
        polluted = {
            "proportion_hints": {
                "typology": "classical_monument",
                "crown_assembly": True,
                "crown_template": "CLASSICAL_CUPOLA",
            },
            "components": [
                {
                    "component_type": "CROWN",
                    "features": ["crown"],
                    "params": {"crown_template": "CLASSICAL_CUPOLA"},
                },
                {
                    "component_type": "MASS_MAIN",
                    "features": ["pilasters", "cornice"],
                    "params": {"facade_profile": "vertical_pilasters"},
                },
            ],
        }
        skip, reason = should_skip_classical_enrichment(text, None, polluted)
        self.assertTrue(skip)
        self.assertEqual(reason, "traditional_east_asian_context")


if __name__ == "__main__":
    unittest.main()
