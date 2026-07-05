"""Tests for assembly repair finalize → capability_gap."""
import unittest

from app.services.assembly_plan_repair import (
    build_capability_gap,
    finalize_assembly_plan_or_gap,
)
from app.services.assembly_plan_validator import AssemblyPlanIssue


class AssemblyPlanRepairTest(unittest.TestCase):
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
