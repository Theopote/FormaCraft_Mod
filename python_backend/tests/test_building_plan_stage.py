"""PR-2: BuildingResearch two-phase — Research → Plan stage separation."""

from __future__ import annotations

import json
import os
import unittest
from pathlib import Path
from unittest.mock import patch

from app.models.building_profile import BuildingProfile
from app.services.building_plan_stage import (
    PLAN_STAGE_MARKER,
    augment_prompts_for_plan_stage,
    build_plan_stage_user_block,
    evaluate_plan_profile_alignment,
    is_research_two_phase_enabled,
    plan_stage_system_augmentation,
    profile_routing_summary,
    score_plan_profile_alignment,
)
from app.services.building_research_agent import synthesize_profile_rule_based


FIXTURES = Path(__file__).resolve().parent.parent / "eval" / "fixtures" / "plans"


class PlanStagePromptTest(unittest.TestCase):
    def test_research_defaults_cannot_override_explicit_user_design(self):
        from app.services.building_plan_stage import PLAN_STAGE_SYSTEM_ADDON

        self.assertIn("Explicit user requirements and opt-outs win", PLAN_STAGE_SYSTEM_ADDON)
        self.assertIn("unspecified defaults", PLAN_STAGE_SYSTEM_ADDON)
        self.assertIn("building or part scope", PLAN_STAGE_SYSTEM_ADDON)
        self.assertNotIn("profile wins for form/style", PLAN_STAGE_SYSTEM_ADDON)

    def test_stage_p_component_types_come_from_capability_catalog(self):
        from app.services.building_plan_stage import (
            build_plan_stage_user_block,
            plannable_component_types,
            registered_component_types,
        )
        from app.services.generation_capability import (
            listed_component_types,
            load_catalog,
            planning_component_exposure,
        )

        catalog = load_catalog(force=True, allow_fallback=False)
        catalog_keys = {str(k).upper() for k in (catalog.get("component_types") or {})}
        listed = set(listed_component_types())
        registered = set(registered_component_types())
        plannable = set(plannable_component_types())
        exposure = planning_component_exposure()
        self.assertEqual(listed, catalog_keys)
        self.assertEqual(registered, catalog_keys)
        # Direct planning set is the supported subset, not every registered type.
        for required in ("BRIDGE", "BRIDGE_CONNECTOR", "PAVING", "TOWER_BASE", "ROOF_STRUCTURE"):
            self.assertIn(required, plannable)
        self.assertNotIn("ASSEMBLY", plannable)
        self.assertNotIn("MODULE", plannable)
        self.assertNotIn("STRUCTURE", plannable)
        from app.services.generation_capability import conditional_component_contracts

        catalog_conditional = conditional_component_contracts()
        self.assertIn("ASSEMBLY", catalog_conditional)
        self.assertIn("STRUCTURE", catalog_conditional)
        self.assertIn(
            "native_typology",
            str(catalog_conditional["STRUCTURE"].get("routes") or []),
        )
        self.assertIn("structure_type", catalog_conditional["STRUCTURE"]["requires"])
        # Default exposure without preset/FBD hides MODULE/ASSEMBLY.
        self.assertNotIn("MODULE", exposure["conditional"])
        self.assertNotIn("ASSEMBLY", exposure["conditional"])
        profile = BuildingProfile(query="test")
        block = build_plan_stage_user_block(profile, "build test")
        self.assertIn("Directly plannable component_type values", block)
        self.assertIn("Conditional catalog types", block)
        self.assertNotIn("- MODULE [", block)
        self.assertNotIn("- ASSEMBLY [", block)
        self.assertNotIn("MASS_ANCILLARY", registered)
        self.assertNotIn("PLAZA_CORE", registered)

        designed = BuildingProfile(query="landmark")
        designed.minecraft_strategy.model_designed_landmark = True
        designed_exposure = planning_component_exposure(designed, user_request="build landmark")
        self.assertNotIn("MODULE", designed_exposure["conditional"])
        self.assertEqual(
            designed_exposure["conditional"]["STRUCTURE"].get("routes"),
            ["parametric_structure_type"],
        )

    def test_two_phase_default_on(self):
        with patch.dict(os.environ, {}, clear=False):
            os.environ.pop("BUILDING_RESEARCH_TWO_PHASE", None)
            self.assertTrue(is_research_two_phase_enabled())

    def test_two_phase_can_disable(self):
        with patch.dict(os.environ, {"BUILDING_RESEARCH_TWO_PHASE": "off"}):
            self.assertFalse(is_research_two_phase_enabled())

    def test_plan_stage_user_block(self):
        profile = synthesize_profile_rule_based(
            "天坛",
            "建造天坛",
            [{"title": "Temple of Heaven", "snippet": "circular tiered altar courtyard", "url": ""}],
        )
        profile = __import__(
            "app.services.building_research_agent",
            fromlist=["finalize_profile_minecraft_strategy"],
        ).finalize_profile_minecraft_strategy(profile, "建造天坛")
        block = build_plan_stage_user_block(profile, "建造天坛")
        self.assertIn("STAGE P", block)
        self.assertIn("BuildingProfile(JSON)", block)
        self.assertIn("天坛", block)
        self.assertIn("OPEN-WORLD RESEARCH OVERRIDE", block)
        self.assertNotIn("vertical_pilasters", block)
        self.assertIn("optional evidence", block.lower())
        self.assertIn("Directly plannable component_type values", block)
        self.assertIn("BRIDGE", block)  # supported catalog types remain visible as tools
        self.assertIn("建造天坛", block)
        self.assertTrue(profile.minecraft_strategy.model_designed_landmark)
        self.assertIsNone(profile.minecraft_strategy.structural_typology)

    def test_typology_stage_block_omits_classical_richness(self):
        from app.services.building_research_agent import finalize_profile_minecraft_strategy
        from app.models.building_profile import BuildingProfile, ProfileMinecraftStrategy

        profile = finalize_profile_minecraft_strategy(
            BuildingProfile(
                query="金门大桥",
                minecraft_strategy=ProfileMinecraftStrategy(),
            ),
            "生成金门大桥",
        )
        block = build_plan_stage_user_block(profile, "生成金门大桥")
        # Catalog may list BRIDGE as a tool; name must not force a bridge kit or typology executor.
        self.assertIn("BRIDGE", block)
        self.assertNotIn("typology:suspension_bridge", block)
        self.assertNotIn("vertical_pilasters", block)
        self.assertNotIn("DECOR_DETAIL cornice", block)
        self.assertTrue(profile.minecraft_strategy.model_designed_landmark)
        self.assertIsNone(profile.minecraft_strategy.structural_typology)
        self.assertNotEqual(profile.minecraft_strategy.recommended_components_source, "hint")

    def test_profile_routing_summary_typology(self):
        from app.services.building_research_agent import finalize_profile_minecraft_strategy
        from app.models.building_profile import BuildingProfile, ProfileMinecraftStrategy

        profile = finalize_profile_minecraft_strategy(
            BuildingProfile(
                query="盖一座天坛",
                minecraft_strategy=ProfileMinecraftStrategy(),
            ),
            "盖一座天坛",
        )
        summary = profile_routing_summary(profile)
        self.assertIn("Model-designed landmark", summary)
        self.assertIn("no fixed typology executor", summary)
        self.assertNotIn("typology:radial_terrace_hall", summary)
        self.assertIsNone(profile.minecraft_strategy.structural_typology)
        self.assertIsNone(profile.minecraft_strategy.landmark_module)
        self.assertNotEqual(profile.minecraft_strategy.recommended_components_source, "hint")

    def test_augment_strips_duplicate_research_block(self):
        profile = synthesize_profile_rule_based("苏州博物馆", "建造苏州博物馆", [])
        old_user = (
            "=== Building Research Profile (open-world) ===\n\nold research\n\n"
            "Culture context here"
        )
        sys_out, user_out = augment_prompts_for_plan_stage(
            profile, "建造苏州博物馆", "base system", old_user
        )
        self.assertIn("STAGE P", user_out)
        self.assertNotIn("Building Research Profile (open-world)", user_out)
        self.assertIn("Culture context here", user_out)
        self.assertIn(PLAN_STAGE_MARKER, sys_out)

    def test_system_augmentation_appended_once(self):
        profile = synthesize_profile_rule_based("test", "build test", [])
        sys1, _ = augment_prompts_for_plan_stage(profile, "build test", "sys", "user")
        sys2, _ = augment_prompts_for_plan_stage(profile, "build test", sys1, "user")
        self.assertEqual(sys1.count(PLAN_STAGE_MARKER), 1)
        self.assertEqual(sys2.count(PLAN_STAGE_MARKER), 1)


class PlanProfileAlignmentTest(unittest.TestCase):
    def _load(self, name: str) -> dict:
        return json.loads((FIXTURES / name).read_text(encoding="utf-8"))

    def test_temple_golden_aligns_with_temple_profile(self):
        plan = self._load("temple_of_heaven_golden.json")
        profile = BuildingProfile(
            query="天坛",
            identity={"name": "天坛", "confidence": 0.9},
            minecraft_strategy={
                "skeleton_type": "RADIAL_RING",
                "recommended_components": ["STRUCTURE"],
                "structural_typology": "radial_terrace_hall",
                "reference_landmark": "temple_of_heaven",
            },
        )
        results = {n: ok for n, ok, _ in evaluate_plan_profile_alignment(plan, profile)}
        self.assertTrue(results.get("plan_reflects_skeleton"))
        self.assertTrue(results.get("plan_uses_recommended_components"))

    def test_siheyuan_profile_alignment(self):
        plan = self._load("siheyuan_courtyard_golden.json")
        profile = BuildingProfile(
            query="四合院",
            identity={"name": "四合院", "confidence": 0.8},
            minecraft_strategy={
                "skeleton_type": "COURTYARD",
                "recommended_components": ["MASS_MAIN", "COURTYARD_SPACE", "ROOF", "ENTRANCE"],
            },
            structure={"distinctive_elements": ["courtyard", "wing", "entrance"]},
        )
        results = {n: ok for n, ok, _ in evaluate_plan_profile_alignment(plan, profile)}
        self.assertTrue(results.get("plan_reflects_skeleton"))
        self.assertTrue(results.get("plan_uses_recommended_components"))
        self.assertTrue(results.get("plan_expresses_complexity"))

    def test_distinguishing_features_alignment_passes_when_reflected(self):
        plan = {
            "style_profile": "Modern_Expressionist",
            "distinguishing_features": [
                "white sail shells",
                "shell roof",
                "waterfront podium",
            ],
            "layout": {"skeleton_type": "CLUSTER"},
            "style_attributes": {
                "decorative_elements": ["white sail shells", "shell roof"],
            },
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "dimensions": {"width": 28, "depth": 18, "height": 12},
                    "features": ["white_sail_shells", "shell_roof"],
                    "params": {"distinctive_features": ["waterfront podium"]},
                },
                {
                    "component_type": "ROOF",
                    "dimensions": {"width": 30, "depth": 20, "height": 8},
                    "features": ["shell roof"],
                    "params": {"roof_type": "shell"},
                },
            ],
        }
        profile = BuildingProfile(
            query="悉尼歌剧院",
            minecraft_strategy={
                "skeleton_type": "CLUSTER",
                "recommended_components": ["MASS_MAIN", "ROOF"],
            },
            structure={
                "distinguishing_features": [
                    "white sail shells",
                    "shell roof",
                    "waterfront podium",
                ],
            },
        )
        summary = score_plan_profile_alignment(plan, profile)
        self.assertTrue(summary["checks"]["plan_reflects_distinguishing_features"])
        self.assertTrue(summary["checks"]["plan_has_distinguishing_features_field"])
        self.assertGreaterEqual(summary["feature_coverage"], 0.66)
        self.assertTrue(summary["passed"])

    def test_distinguishing_features_alignment_fails_when_missing(self):
        plan = {
            "layout": {"skeleton_type": "RECT_SINGLE"},
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "dimensions": {"width": 20, "depth": 14, "height": 8},
                    "features": ["pilasters"],
                    "params": {"facade_profile": "vertical_pilasters"},
                },
                {"component_type": "ROOF", "dimensions": {"width": 22, "depth": 16, "height": 5}, "features": []},
            ],
        }
        profile = BuildingProfile(
            query="悉尼歌剧院",
            minecraft_strategy={
                "skeleton_type": "RECT_SINGLE",
                "recommended_components": ["MASS_MAIN", "ROOF"],
            },
            structure={
                "distinguishing_features": [
                    "white sail shells",
                    "shell roof",
                    "waterfront podium",
                ],
            },
        )
        summary = score_plan_profile_alignment(plan, profile)
        self.assertFalse(summary["checks"]["plan_reflects_distinguishing_features"])
        self.assertFalse(summary["passed"])


class TwoPhaseIntegrationTest(unittest.TestCase):
    def test_single_phase_injects_research_text_when_two_phase_off(self):
        """BUILDING_RESEARCH_TWO_PHASE=off 时应走 PR-1 单阶段文本注入。"""
        with patch.dict(os.environ, {"BUILDING_RESEARCH_TWO_PHASE": "off"}):
            self.assertFalse(is_research_two_phase_enabled())

    def test_profile_json_roundtrip_in_stage_block(self):
        profile = synthesize_profile_rule_based("Eiffel Tower", "build Eiffel Tower", [])
        block = build_plan_stage_user_block(profile, "build Eiffel Tower")
        json_start = block.index("{", block.index("BuildingProfile(JSON):"))
        rest = block[json_start:]
        cut = rest.find("\n\nPlanning checklist")
        payload = rest if cut < 0 else rest[:cut]
        parsed = json.loads(payload)
        self.assertEqual(parsed["query"], "Eiffel Tower")


if __name__ == "__main__":
    unittest.main()
