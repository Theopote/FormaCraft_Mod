"""Architectural richness enrichment for open-world LlmPlan."""

import unittest


class TestPlanArchitecturalEnrichment(unittest.TestCase):
    def test_enrich_adds_facade_and_decor_for_louvre(self):
        from app.models.building_profile import BuildingProfile, ProfileMinecraftStrategy
        from app.services.plan_architectural_enrichment import enrich_llm_plan_architectural_detail

        plan = {
            "mode": "build",
            "style_profile": "French_Classical",
            "anchor": {"x": 0, "y": 64, "z": 0},
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 20, "depth": 14, "height": 8},
                    "features": [],
                    "params": {},
                },
                {
                    "component_type": "FACADE_WINDOWS",
                    "relative_position": {"x": 0, "y": 1, "z": 0},
                    "dimensions": {"width": 20, "depth": 1, "height": 6},
                    "features": [],
                    "params": {},
                },
            ],
        }
        profile = BuildingProfile(
            query="louvre",
            minecraft_strategy=ProfileMinecraftStrategy(landmark_module=None),
        )
        out = enrich_llm_plan_architectural_detail(plan, user_text="louvre museum", profile=profile)
        types = [c["component_type"] for c in out["components"]]
        self.assertIn("FOUNDATION", types)
        self.assertIn("ROOF", types)
        self.assertIn("DECOR_DETAIL", types)
        self.assertIn("proportion_hints", out)
        hints = out.get("proportion_hints") or {}
        if "louvre" in "louvre museum".lower():
            self.assertIn("CROWN", types)
            self.assertEqual(hints.get("crown_template"), "CLASSICAL_CUPOLA")
            self.assertEqual(hints.get("roof_specialty"), "mansard_dormer")
        roof = next(c for c in out["components"] if c["component_type"] == "ROOF")
        self.assertEqual(roof["params"].get("roof_type"), "mansard")
        self.assertTrue(roof["params"].get("roof_dormers"))
        mass = next(c for c in out["components"] if c["component_type"] == "MASS_MAIN")
        self.assertEqual(mass["params"].get("facade_profile"), "vertical_pilasters")
        self.assertGreaterEqual(mass["dimensions"]["height"], 10)

    def test_profile_enrichment_expands_recommended_components(self):
        from app.models.building_profile import BuildingProfile
        from app.services.plan_architectural_enrichment import enrich_profile_architectural_detail

        profile = enrich_profile_architectural_detail(
            BuildingProfile(query="palace"),
            "生成一座古典宫殿",
        )
        rec = [c.upper() for c in profile.minecraft_strategy.recommended_components]
        self.assertIn("DECOR_DETAIL", rec)
        self.assertIn("FOUNDATION", rec)

    def test_typology_profile_skips_compositional_enrichment(self):
        from app.models.building_profile import BuildingProfile, ProfileMinecraftStrategy
        from app.services.plan_architectural_enrichment import enrich_profile_architectural_detail

        profile = enrich_profile_architectural_detail(
            BuildingProfile(
                query="金门大桥",
                minecraft_strategy=ProfileMinecraftStrategy(
                    structural_typology="suspension_bridge",
                    reference_landmark="golden_gate_bridge",
                ),
            ),
            "在锚点位置生成金门大桥",
        )
        self.assertEqual(profile.minecraft_strategy.recommended_components, ["STRUCTURE"])
        self.assertIn("FORBID", profile.minecraft_strategy.notes or "")

    def test_typology_plan_not_enriched_with_house_facade(self):
        from app.services.plan_architectural_enrichment import enrich_llm_plan_architectural_detail

        plan = {
            "mode": "build",
            "components": [
                {
                    "component_type": "STRUCTURE",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 9, "depth": 180, "height": 44},
                    "features": ["typology:suspension_bridge"],
                    "params": {
                        "typology_id": "suspension_bridge",
                        "reference_landmark": "golden_gate_bridge",
                    },
                },
                {
                    "component_type": "MASS_MAIN",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 12, "depth": 12, "height": 7},
                    "features": [],
                    "params": {},
                },
            ],
        }
        out = enrich_llm_plan_architectural_detail(
            plan,
            user_text="金门大桥",
        )
        self.assertEqual(len(out["components"]), 2)
        self.assertNotIn("floor_cornice", (out.get("proportion_hints") or {}))


if __name__ == "__main__":
    unittest.main()
