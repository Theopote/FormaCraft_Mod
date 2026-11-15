"""LlmPlan validation repair and profile fallback."""

import unittest


class TestLlmPlanRepair(unittest.TestCase):
    def test_invalid_open_world_plan_emits_gap_not_house_kit(self):
        from unittest.mock import Mock, patch

        from app.models.building_profile import BuildingProfile, ProfileMinecraftStrategy, ProfileScaleHints
        from app.services.llm_plan_normalization import _normalize_llm_plan_output
        from app.services.llm_plan_repair import repair_llm_plan_validation

        profile = BuildingProfile(
            query="louvre",
            minecraft_strategy=ProfileMinecraftStrategy(
                landmark_module=None,
                structural_typology=None,
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
        req.outline = None
        req.player = Mock()
        req.player.pos = Mock(x=1280, y=71, z=-220)
        req.player.facing = "SOUTH"

        def normalize_fn(plan, build_req, **_kwargs):
            return _normalize_llm_plan_output(plan, build_req, building_profile=profile)

        with patch("app.services.llm_plan_repair.build_minimal_plan_from_profile") as fallback:
            out = repair_llm_plan_validation(
                bad_plan,
                req,
                user_text="louvre museum",
                building_profile=profile,
                normalize_fn=normalize_fn,
            )
            fallback.assert_not_called()
        self.assertEqual(out.get("plan_status"), "capability_gap")

    def test_fixed_typology_route_may_still_use_profile_fallback(self):
        from unittest.mock import Mock

        from app.models.building_profile import BuildingProfile, ProfileMinecraftStrategy, ProfileScaleHints
        from app.services.llm_plan_normalization import _normalize_llm_plan_output
        from app.services.llm_plan_repair import repair_llm_plan_validation

        profile = BuildingProfile(
            query="pagoda",
            minecraft_strategy=ProfileMinecraftStrategy(
                landmark_module=None,
                structural_typology="dense_eaves_pagoda",
                recommended_components=["STRUCTURE"],
            ),
            scale_hints=ProfileScaleHints(typical_width_blocks=20, typical_depth_blocks=20, typical_height_blocks=40),
        )
        bad_plan = {"mode": "build", "style_profile": "DEFAULT"}
        req = Mock()
        req.userMessage = "dense eaves pagoda"
        req.selection = None
        req.brushSelection = None
        req.world = None
        req.outline = None
        req.symmetry = None
        req.player = Mock()
        req.player.pos = Mock(x=10, y=64, z=10)
        req.player.facing = "SOUTH"

        def normalize_fn(plan, build_req, **_kwargs):
            return _normalize_llm_plan_output(plan, build_req, building_profile=profile)

        out = repair_llm_plan_validation(
            bad_plan,
            req,
            user_text="dense eaves pagoda",
            building_profile=profile,
            normalize_fn=normalize_fn,
        )
        self.assertNotEqual(out.get("plan_status"), "capability_gap")
        types = [c["component_type"] for c in out.get("components") or []]
        self.assertIn("STRUCTURE", types)

    def test_golden_gate_profile_typology_first(self):
        from app.models.building_profile import BuildingProfile
        from app.services.building_research_agent import finalize_profile_minecraft_strategy

        profile = finalize_profile_minecraft_strategy(
            BuildingProfile(query="bridge"),
            "生成旧金山大桥",
        )
        self.assertIsNone(profile.minecraft_strategy.landmark_module)
        self.assertIsNone(profile.minecraft_strategy.structural_typology)
        self.assertTrue(profile.minecraft_strategy.model_designed_landmark)
        self.assertIsNone(profile.minecraft_strategy.reference_landmark)

    def test_profile_fallback_preserves_facing_and_symmetry(self):
        from types import SimpleNamespace

        from app.models.building_profile import BuildingProfile, ProfileMinecraftStrategy, ProfileScaleHints
        from app.models.request import SymmetryConstraint
        from app.services.llm_plan_repair import build_minimal_plan_from_profile

        profile = BuildingProfile(
            query="memorial hall",
            minecraft_strategy=ProfileMinecraftStrategy(
                landmark_module=None,
                recommended_components=["MASS_MAIN", "ROOF", "ENTRANCE"],
            ),
            scale_hints=ProfileScaleHints(
                typical_width_blocks=36, typical_depth_blocks=24, typical_height_blocks=16
            ),
        )
        req = SimpleNamespace(
            userMessage="建造东西轴线对称纪念馆，面朝东方",
            selection=None,
            brushSelection=None,
            world=None,
            outline=None,
            symmetry=SymmetryConstraint(mode="MIRROR_X"),
            player=SimpleNamespace(
                pos=SimpleNamespace(x=100, y=70, z=200),
                facing="EAST",
            ),
        )
        minimal = build_minimal_plan_from_profile(
            profile,
            req,
            "建造东西轴线对称纪念馆，面朝东方，保留地形",
            prior_plan={
                "global_constraints": {
                    "facing": "SOUTH",
                    "symmetry": "NONE",
                    "terrain_strategy": "PRESERVE",
                }
            },
        )
        gc = minimal["global_constraints"]
        self.assertEqual(gc["facing"], "EAST")
        self.assertEqual(gc["symmetry"], "MIRROR_X")
        self.assertEqual(gc["terrain_strategy"], "PRESERVE")
        self.assertTrue(minimal["genome"]["symmetry"]["mirror"])


if __name__ == "__main__":
    unittest.main()
