"""LlmPlan validation repair and profile fallback."""

import unittest


class TestLlmPlanRepair(unittest.TestCase):
    def test_invalid_plan_falls_back_to_profile(self):
        from unittest.mock import Mock

        from app.models.building_profile import BuildingProfile, ProfileMinecraftStrategy, ProfileScaleHints
        from app.services.ai_planner import _normalize_llm_plan_output
        from app.services.llm_plan_repair import repair_llm_plan_validation

        profile = BuildingProfile(
            query="louvre",
            minecraft_strategy=ProfileMinecraftStrategy(
                landmark_module=None,
                recommended_components=["MASS_MAIN", "ROOF", "ENTRANCE"],
            ),
            scale_hints=ProfileScaleHints(typical_width_blocks=40, typical_depth_blocks=30, typical_height_blocks=14),
        )
        bad_plan = {
            "mode": "build",
            "style_profile": "DEFAULT",
        }
        req = Mock()
        req.userMessage = "louvre museum"
        req.selection = None
        req.brushSelection = None
        req.world = None

        def normalize_fn(plan, build_req, **_kwargs):
            return _normalize_llm_plan_output(plan, build_req, building_profile=profile)

        out = repair_llm_plan_validation(
            bad_plan,
            req,
            user_text="louvre museum",
            building_profile=profile,
            normalize_fn=normalize_fn,
        )
        self.assertNotEqual(out.get("plan_status"), "capability_gap")
        self.assertTrue(out.get("components"))
        types = [c["component_type"] for c in out["components"]]
        self.assertIn("MASS_MAIN", types)

    def test_golden_gate_profile_typology_first(self):
        from app.models.building_profile import BuildingProfile, ProfileMinecraftStrategy
        from app.services.building_research_agent import finalize_profile_minecraft_strategy

        profile = finalize_profile_minecraft_strategy(
            BuildingProfile(query="bridge"),
            "生成旧金山大桥",
        )
        self.assertIsNone(profile.minecraft_strategy.landmark_module)
        self.assertEqual(profile.minecraft_strategy.structural_typology, "suspension_bridge")
        self.assertEqual(profile.minecraft_strategy.reference_landmark, "golden_gate_bridge")


if __name__ == "__main__":
    unittest.main()
