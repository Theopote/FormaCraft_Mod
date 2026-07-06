"""P3: enrichment_guard contract + lattice false-positive regression."""

from __future__ import annotations

import unittest

from app.services.plan_architectural_enrichment import enrich_llm_plan_architectural_detail
from app.services.plan_specificity_guard import should_skip_classical_enrichment


class NonClassicalJavaGuardParityTest(unittest.TestCase):
    def test_enrichment_guard_emitted_for_zaha_like_profile(self):
        plan = {
            "mode": "build",
            "style_profile": "DEFAULT",
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 20, "depth": 14, "height": 10},
                    "features": [],
                    "params": {"facade_profile": "vertical_pilasters"},
                }
            ],
        }
        out = enrich_llm_plan_architectural_detail(
            plan,
            user_text="zaha hadid guangzhou opera house",
            profile=None,
        )
        guard = out.get("enrichment_guard", "")
        self.assertTrue(
            str(guard).startswith("non_classical_marker:"),
            f"expected non_classical_marker guard, got {guard!r}",
        )

    def test_chinese_garden_enrichment_not_guarded_by_lattice(self):
        plan = {
            "mode": "build",
            "style_profile": "DEFAULT",
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 10, "depth": 8, "height": 5},
                    "features": [],
                    "params": {"roof_type": "double_gable"},
                },
                {
                    "component_type": "FACADE_WINDOWS",
                    "relative_position": {"x": 0, "y": 2, "z": -4},
                    "dimensions": {"width": 2, "depth": 1, "height": 2},
                    "features": ['{"tags": ["chinese", "lattice"]}'],
                    "params": {"window_style": "lattice"},
                },
            ],
            "genome": {
                "culturalStyle": {
                    "region": "east_asia",
                    "era": "traditional",
                    "keywords": ["garden", "pavilion", "chinese"],
                },
            },
        }
        skip, _ = should_skip_classical_enrichment("中式园林", None, plan)
        self.assertFalse(skip)

        out = enrich_llm_plan_architectural_detail(plan, user_text="中式园林", profile=None)
        self.assertNotIn("enrichment_guard", out)


if __name__ == "__main__":
    unittest.main()
