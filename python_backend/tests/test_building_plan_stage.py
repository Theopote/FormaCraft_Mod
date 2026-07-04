"""PR-2: BuildingResearch two-phase — Research → Plan stage separation."""

from __future__ import annotations

import json
import os
import unittest
from pathlib import Path
from unittest.mock import patch

from app.models.building_profile import BuildingProfile
from app.services.building_plan_stage import (
    augment_prompts_for_plan_stage,
    build_plan_stage_user_block,
    evaluate_plan_profile_alignment,
    is_research_two_phase_enabled,
    plan_stage_system_augmentation,
)
from app.services.building_research_agent import synthesize_profile_rule_based


FIXTURES = Path(__file__).resolve().parent.parent / "eval" / "fixtures" / "plans"


class PlanStagePromptTest(unittest.TestCase):
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
        block = build_plan_stage_user_block(profile, "建造天坛")
        self.assertIn("STAGE P", block)
        self.assertIn("BuildingProfile(JSON)", block)
        self.assertIn("天坛", block)
        self.assertIn("MASS_MAIN", block)
        self.assertIn("建造天坛", block)

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
        self.assertIn(plan_stage_system_augmentation(), sys_out)

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
                "recommended_components": ["MODULE", "MASS_MAIN", "ROOF"],
                "landmark_module": "temple_of_heaven",
            },
        )
        results = {n: ok for n, ok, _ in evaluate_plan_profile_alignment(plan, profile)}
        self.assertTrue(results.get("plan_reflects_skeleton"))
        self.assertTrue(results.get("plan_uses_recommended_components"))
        self.assertTrue(results.get("plan_uses_landmark_module"))

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


class TwoPhaseIntegrationTest(unittest.TestCase):
    def test_single_phase_injects_research_text_when_two_phase_off(self):
        """BUILDING_RESEARCH_TWO_PHASE=off 时应走 PR-1 单阶段文本注入。"""
        with patch.dict(os.environ, {"BUILDING_RESEARCH_TWO_PHASE": "off"}):
            self.assertFalse(is_research_two_phase_enabled())

    def test_profile_json_roundtrip_in_stage_block(self):
        profile = synthesize_profile_rule_based("Eiffel Tower", "build Eiffel Tower", [])
        block = build_plan_stage_user_block(profile, "build Eiffel Tower")
        json_start = block.index("{", block.index("BuildingProfile(JSON):"))
        payload = block[json_start:].split("\n\nPlanning checklist:", 1)[0]
        parsed = json.loads(payload)
        self.assertEqual(parsed["query"], "Eiffel Tower")


if __name__ == "__main__":
    unittest.main()
