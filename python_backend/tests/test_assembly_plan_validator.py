"""Tests for assembly plan validation and auto-preset injection."""
import unittest

from app.services.assembly_plan_validator import (
    auto_apply_assembly_presets,
    format_repair_prompt,
    resolve_preset_for_intent,
    validate_assembly_plan,
)


class AssemblyPlanValidatorTest(unittest.TestCase):
    def test_resolve_spiral_preset(self):
        self.assertEqual("spiral_watchtower", resolve_preset_for_intent("原创螺旋瞭望塔"))

    def test_resolve_bridge_preset(self):
        self.assertEqual("suspension_bridge_simple", resolve_preset_for_intent("悬索桥 span 40"))

    def test_auto_injects_preset_for_empty_assembly(self):
        plan = {
            "components": [
                {
                    "component_type": "ASSEMBLY",
                    "params": {"assembly": {}},
                }
            ]
        }
        out = auto_apply_assembly_presets(plan, "螺旋瞭望塔")
        assembly = out["components"][0]["params"]["assembly"]
        self.assertEqual("spiral_watchtower", assembly.get("preset"))

    def test_rejects_nested_assembly_in_mass(self):
        plan = {
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "params": {"assembly": {"graph": {"components": []}}},
                }
            ]
        }
        issues = validate_assembly_plan(plan)
        self.assertTrue(any(i.code == "E_NESTED_ASSEMBLY_IN_MASS" for i in issues))

    def test_rejects_unknown_port(self):
        plan = {
            "components": [
                {
                    "component_type": "ASSEMBLY",
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
            ]
        }
        issues = validate_assembly_plan(plan)
        self.assertTrue(any(i.code == "E_CONN_UNKNOWN_PORT" for i in issues))

    def test_accepts_valid_preset_shorthand(self):
        plan = {
            "components": [
                {
                    "component_type": "ASSEMBLY",
                    "params": {
                        "assembly": {
                            "preset": "spiral_watchtower",
                            "presetParams": {"height": 24, "twistTurns": 0.5},
                        }
                    },
                }
            ]
        }
        issues = [i for i in validate_assembly_plan(plan) if i.severity == "ERROR"]
        self.assertEqual([], issues)

    def test_format_repair_prompt_includes_errors(self):
        plan = {"components": []}
        from app.services.assembly_plan_validator import AssemblyPlanIssue

        text = format_repair_prompt(
            plan,
            [AssemblyPlanIssue("x", "E_TEST", "boom")],
        )
        self.assertIn("E_TEST", text)
        self.assertIn("preset", text.lower())


if __name__ == "__main__":
    unittest.main()
