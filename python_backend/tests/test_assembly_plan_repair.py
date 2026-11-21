"""Tests for assembly repair finalize → capability_gap."""
from copy import deepcopy
import json
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from app.services.assembly_plan_repair import (
    build_capability_gap,
    finalize_assembly_plan_or_gap,
    maybe_repair_assembly_plan,
)
from app.services.assembly_plan_validator import AssemblyPlanIssue


class AssemblyPlanRepairTest(unittest.TestCase):
    def test_loft_feedback_reaches_model_and_repaired_plan_is_revalidated(self):
        request = "用石英建一座原创折光匣，保留高 9 格的斜面"
        plan = {
            "mode": "build", "anchor": {"x": 0, "y": 64, "z": 0},
            "style_attributes": {"wall_material": "minecraft:smooth_quartz"},
            "components": [{
                "component_type": "ASSEMBLY", "relative_position": {"x": 0, "y": 0, "z": 0},
                "dimensions": {"width": 24, "depth": 14, "height": 9}, "features": [],
                "params": {"assembly": {"ops": [{
                    "op": "LOFT_SURFACE", "material": "minecraft:smooth_quartz",
                    "sections": [
                        {"at": {"x": 0, "y": 5, "z": 0}, "profilePoints": [{"x": 3, "y": 0}, {"x": 20, "y": 0}]},
                        {"at": {"x": 0, "y": 8, "z": 13}, "profilePoints": [
                            {"x": 8, "y": 0}, {"x": 11, "y": 0}, {"x": 15, "y": 0},
                        ]},
                    ],
                }]}},
            }],
        }
        plan["components"].append({
            "component_type": "PRIMITIVE", "relative_position": {"x": 0, "y": 0, "z": 0},
            "dimensions": {"width": 24, "depth": 14, "height": 5}, "features": [],
            "params": {"kind": "trapezoid", "end_width": 16, "material": "minecraft:smooth_quartz"},
        })
        corrected = deepcopy(plan)
        corrected["components"][0]["params"]["assembly"]["ops"][0]["sections"][1]["profilePoints"].pop(1)
        dropped = deepcopy(plan)
        dropped["components"][0]["component_type"] = "MASS_MAIN"
        dropped["components"][0]["params"] = {}
        changed_volume = deepcopy(corrected)
        changed_volume["components"][1]["params"]["end_width"] = 12
        moved_plan = {**corrected, "anchor": {"x": 0, "y": 80, "z": 0}}
        changed_palette = {**corrected, "style_attributes": {"wall_material": "minecraft:bricks"}}
        for case, candidate in (("corrected", corrected), ("unchanged", plan),
                                ("dropped", dropped), ("changed_volume", changed_volume),
                                ("moved_plan", moved_plan), ("changed_palette", changed_palette)):
            with self.subTest(case=case), patch(
                "app.services.assembly_plan_repair.ASSEMBLY_REPAIR_MAX_RETRIES", 1
            ), patch("app.services.ai_planner.call_chat_with_llm_plan_response_formats") as call:
                response = SimpleNamespace(choices=[
                    SimpleNamespace(message=SimpleNamespace(content=json.dumps(candidate))),
                ])
                call.return_value = response, "json"
                result = maybe_repair_assembly_plan(
                    None, "test-model", [{"role": "user", "content": request}], deepcopy(plan), request,
                    call_with_timeout=None, timeout_sec=1, prefer_json_object=True,
                    normalize_fn=lambda payload, _: payload, req=SimpleNamespace(userMessage=request),
                )
                call.assert_called_once()
                messages = call.call_args.kwargs["messages"]
                self.assertEqual(request, messages[0]["content"])
                self.assertIn("ops[0].sections[1].profilePoints", messages[-1]["content"])
                self.assertIn("Expected 2 profile points, got 3", messages[-1]["content"])
                if case == "corrected":
                    self.assertEqual(corrected["components"], result["components"])
                    self.assertNotEqual("capability_gap", result.get("plan_status"))
                else:
                    self.assertEqual("capability_gap", result["plan_status"])
                    self.assertEqual("E_LOFT_PROFILE_COUNT", result["capability_gap"]["code"])
                    self.assertEqual(plan["components"], result["components"])
                    self.assertEqual(plan["anchor"], result["anchor"])
                    self.assertEqual(plan.get("style_attributes"), result.get("style_attributes"))

    def test_build_capability_gap_from_issues(self):
        gap = build_capability_gap([
            AssemblyPlanIssue("components[0].params.assembly", "E_CONN_UNKNOWN_PORT", "bad port"),
        ])
        self.assertEqual("E_CONN_UNKNOWN_PORT", gap["code"])
        self.assertIn("bad port", gap["message"])
        self.assertTrue(gap["suggestions"])

    def test_finalize_emits_gap_for_unresolved_assembly_errors(self):
        plan = {
            "mode": "build",
            "anchor": {"x": 0, "y": 64, "z": 0},
            "components": [
                {
                    "component_type": "ASSEMBLY",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 10, "depth": 10, "height": 20},
                    "params": {
                        "assembly": {
                            "graph": {
                                "components": [
                                    {"id": "A", "type": "SHELL_BOX", "w": 8, "d": 8, "h": 12},
                                    {"id": "B", "type": "SHELL_BOX", "w": 6, "d": 6, "h": 8},
                                ],
                                "connections": [
                                    {"from": "A.bad_port", "to": "B.bottom_center"},
                                ],
                            }
                        }
                    },
                }
            ],
        }
        out = finalize_assembly_plan_or_gap(plan, "螺旋瞭望塔 ASSEMBLY")
        self.assertEqual("capability_gap", out.get("plan_status"))
        self.assertIsNotNone(out.get("capability_gap"))
        self.assertEqual("E_CONN_UNKNOWN_PORT", out["capability_gap"]["code"])

    def test_finalize_skips_non_assembly_plans(self):
        plan = {
            "mode": "build",
            "anchor": {"x": 0, "y": 64, "z": 0},
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 10, "depth": 8, "height": 6},
                    "params": {},
                }
            ],
        }
        out = finalize_assembly_plan_or_gap(plan, "建一栋中式别墅")
        self.assertNotEqual("capability_gap", out.get("plan_status"))

    def test_finalize_preserves_existing_capability_gap(self):
        plan = {
            "mode": "build",
            "anchor": {"x": 0, "y": 64, "z": 0},
            "plan_status": "capability_gap",
            "capability_gap": {"code": "E_CUSTOM", "message": "unsupported"},
        }
        out = finalize_assembly_plan_or_gap(plan, "自由几何")
        self.assertEqual("E_CUSTOM", out["capability_gap"]["code"])


if __name__ == "__main__":
    unittest.main()
