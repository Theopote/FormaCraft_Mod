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
from app.services.assembly_plan_validator import AssemblyPlanIssue, validate_assembly_plan


class AssemblyPlanRepairTest(unittest.TestCase):
    def test_bezier_control_grid_is_preserved_and_incomplete_grid_reports_path(self):
        grid = [[{"x": x, "y": 8 if z in (3, 6) else 0, "z": z}
                 for z in (0, 3, 6, 9)] for x in (0, 3, 6, 9)]
        op = {"op": "BEZIER_SURFACE", "points": grid, "uSamples": 12, "vSamples": 12}
        plan = {"components": [{"component_type": "ASSEMBLY", "params": {"assembly": {"ops": [op]}}}]}
        for points in (grid, [point for row in grid for point in row]):
            op["points"] = points
            before = deepcopy(plan)
            self.assertEqual([], validate_assembly_plan(plan))
            self.assertEqual(before, plan)
        op["points"] = grid
        grid[1].pop()
        issues = validate_assembly_plan(plan)
        self.assertEqual(["E_BEZIER_POINTS_INVALID"], [issue.code for issue in issues])
        self.assertTrue(issues[0].path.endswith("ops[0].points"))

    def test_revolve_profiles_pass_unchanged_and_bad_radius_reports_path(self):
        tiers = [
            {"op": "REVOLVE_SURFACE", "profilePoints": [{"x": 8, "y": 4}, {"x": 5, "y": 7}],
             "material": "minecraft:smooth_quartz"},
            {"op": "REVOLVE_SURFACE", "profilePoints": [{"x": 5, "y": 9}, {"x": 2, "y": 12}],
             "material": "minecraft:prismarine"},
        ]
        plan = {"components": [{"component_type": "ASSEMBLY", "params": {"assembly": {"ops": tiers}}}]}
        before = deepcopy(plan)
        self.assertEqual([], validate_assembly_plan(plan))
        self.assertEqual(before, plan)
        tiers[1]["profilePoints"][1]["x"] = -2
        issues = validate_assembly_plan(plan)
        self.assertEqual(["E_REVOLVE_PROFILE_INVALID"], [issue.code for issue in issues])
        self.assertTrue(issues[0].path.endswith("ops[1].profilePoints"))

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
        plan["components"][0]["params"]["assembly"]["ops"].append({
            "op": "REVOLVE_SURFACE", "profilePoints": [{"x": 4, "y": 1}, {"x": 2, "y": 3}],
            "material": "minecraft:prismarine",
        })
        corrected = deepcopy(plan)
        corrected["components"][0]["params"]["assembly"]["ops"][0]["sections"][1]["profilePoints"].pop(1)
        dropped = deepcopy(plan)
        dropped["components"][0]["component_type"] = "MASS_MAIN"
        dropped["components"][0]["params"] = {}
        changed_volume = deepcopy(corrected)
        changed_volume["components"][1]["params"]["end_width"] = 12
        changed_surface = deepcopy(corrected)
        changed_surface["components"][0]["params"]["assembly"]["ops"][1]["profilePoints"][0]["x"] = 5
        moved_plan = {**corrected, "anchor": {"x": 0, "y": 80, "z": 0}}
        changed_palette = {**corrected, "style_attributes": {"wall_material": "minecraft:bricks"}}
        for case, candidate in (("corrected", corrected), ("unchanged", plan),
                                ("dropped", dropped), ("changed_volume", changed_volume),
                                ("changed_surface", changed_surface),
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
                self.assertIn("Preserve all other operations", messages[-1]["content"])
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
