"""Architectural richness enrichment for open-world LlmPlan."""

import unittest


class TestPlanArchitecturalEnrichment(unittest.TestCase):
    def test_dimensioned_stair_building_keeps_shell_and_roof(self):
        import copy
        from app.services.plan_architectural_enrichment import enrich_llm_plan_architectural_detail
        plan = {"mode": "build", "components": [
            {"component_type": "MASS_MAIN", "dimensions": {"width": 15, "depth": 13, "height": 11},
             "params": {"floor_height": 5, "floor_count": 2}},
            {"component_type": "ROOF", "relative_position": {"x": -1, "y": 10, "z": -1},
             "params": {"roof_type": "flat"}}]}
        original = copy.deepcopy(plan)
        self.assertEqual(original, enrich_llm_plan_architectural_detail(plan, user_text="两层住宅，楼梯，平屋顶"))

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

    def test_sydney_opera_skips_classical_enrichment(self):
        from app.models.building_profile import BuildingProfile, ProfileForm, ProfileStructure
        from app.services.plan_architectural_enrichment import enrich_llm_plan_architectural_detail

        profile = BuildingProfile(
            query="悉尼歌剧院",
            form=ProfileForm(footprint="freeform", massing=["shell clusters", "waterfront"]),
            structure=ProfileStructure(
                distinguishing_features=["white sail shells", "shell roof", "waterfront podium"],
            ),
        )
        plan = {
            "mode": "build",
            "style_profile": "Modern_Expressionist",
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 28, "depth": 18, "height": 10},
                    "features": [],
                    "params": {},
                },
            ],
        }
        out = enrich_llm_plan_architectural_detail(
            plan,
            user_text="悉尼歌剧院",
            profile=profile,
        )
        types = [c["component_type"] for c in out["components"]]
        self.assertNotIn("CROWN", types)
        self.assertNotIn("DECOR_DETAIL", types)
        mass = out["components"][0]
        self.assertNotEqual(mass["params"].get("facade_profile"), "vertical_pilasters")
        self.assertIn("enrichment_guard", out)
        self.assertIn("shell", " ".join(mass.get("features") or []).lower())

    def test_zaha_profile_skips_classical_notes(self):
        from app.models.building_profile import BuildingProfile, ProfileIdentity, ProfileStructure
        from app.services.plan_architectural_enrichment import enrich_profile_architectural_detail

        profile = enrich_profile_architectural_detail(
            BuildingProfile(
                query="扎哈体育场",
                identity=ProfileIdentity(architect="Zaha Hadid", style="parametric modern"),
                structure=ProfileStructure(
                    distinguishing_features=["curved facade", "parametric mesh", "cantilever roof"],
                ),
            ),
            "扎哈风格的体育场",
        )
        notes = profile.minecraft_strategy.notes or ""
        self.assertNotIn("vertical_pilasters", notes)
        self.assertIn("Specificity guard", notes)
        self.assertNotIn("DECOR_DETAIL", profile.minecraft_strategy.recommended_components)


if __name__ == "__main__":
    unittest.main()
