"""Deterministic Design Authority regressions (no live LLM)."""

from __future__ import annotations

import copy
import unittest
from unittest.mock import Mock

from app.models.building_profile import BuildingProfile, ProfileStructure
from app.services.assembly_plan_repair import finalize_assembly_plan_or_gap
from app.services.assembly_plan_validator import (
    auto_apply_assembly_presets,
    resolve_preset_for_intent,
    validate_assembly_plan,
)
from app.services.llm_plan_normalization import _normalize_llm_plan_output


class DesignAuthorityRegressionsTest(unittest.TestCase):
    def test_keyword_spiral_gothic_bridge_do_not_select_preset(self):
        for text in (
            "漂浮在海面上的螺旋图书馆",
            "哥特风格的壳塔",
            "悬索桥连接两岸",
        ):
            self.assertIsNone(resolve_preset_for_intent(text))
            plan = {
                "components": [
                    {"component_type": "ASSEMBLY", "params": {"assembly": {}}},
                ]
            }
            out = auto_apply_assembly_presets(copy.deepcopy(plan), text)
            self.assertNotIn("preset", out["components"][0]["params"]["assembly"])

    def test_explicit_preset_opt_in_is_applied(self):
        plan = {
            "components": [
                {"component_type": "ASSEMBLY", "params": {"assembly": {}}},
            ]
        }
        out = auto_apply_assembly_presets(
            plan, "请生成一座塔 preset:spiral_watchtower"
        )
        self.assertEqual(
            "spiral_watchtower",
            out["components"][0]["params"]["assembly"]["preset"],
        )

    def test_incomplete_assembly_reports_gap_not_unrelated_preset(self):
        plan = {
            "mode": "build",
            "components": [
                {"component_type": "ASSEMBLY", "params": {"assembly": {}}},
            ],
        }
        issues = validate_assembly_plan(plan)
        self.assertTrue(any(i.code == "E_ASSEMBLY_EMPTY" for i in issues))
        out = finalize_assembly_plan_or_gap(copy.deepcopy(plan), "赛博朋克风格的中式书院")
        self.assertEqual("capability_gap", out.get("plan_status"))
        assembly = out["components"][0]["params"]["assembly"]
        self.assertFalse(assembly.get("preset"))

    def test_ai_xieshan_and_courtyard_not_silently_rewritten(self):
        profile = BuildingProfile(
            query="没有任何传统屋顶关键词的中式宅院",
            structure=ProfileStructure(distinguishing_features=["courtyard", "xieshan"]),
        )
        plan = {
            "mode": "build",
            "style_profile": "Chinese_Classical",
            "genome": {"culturalStyle": {"region": "chinese"}},
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "main",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 26, "depth": 20, "height": 9},
                    "features": [],
                    "params": {
                        "component_id": "hall",
                        "roof_type": "xieshan",
                        "plan_type": "courtyard",
                        "courtyard_ratio": 0.4,
                    },
                },
                {
                    "component_type": "COURTYARD",
                    "slot_id": "main",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 8, "depth": 8, "height": 1},
                    "features": [],
                    "params": {"component_id": "yard", "host_id": "hall"},
                },
            ],
        }
        req = Mock()
        req.userMessage = "建造一座中式宅院"
        req.selection = None
        req.brushSelection = None
        out = _normalize_llm_plan_output(copy.deepcopy(plan), req=req, building_profile=profile)
        mass = next(c for c in out["components"] if c["component_type"] == "MASS_MAIN")
        self.assertEqual("xieshan", mass["params"]["roof_type"])
        self.assertEqual("courtyard", mass["params"]["plan_type"])
        self.assertIn("COURTYARD", [c["component_type"] for c in out["components"]])


if __name__ == "__main__":
    unittest.main()
