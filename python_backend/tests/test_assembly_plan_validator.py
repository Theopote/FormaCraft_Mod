"""Tests for assembly plan validation and explicit-only preset injection."""
import unittest

from app.services.assembly_plan_validator import (
    auto_apply_assembly_presets,
    format_repair_prompt,
    resolve_preset_for_intent,
    validate_assembly_plan,
)
from app.services.assembly_plan_repair import finalize_assembly_plan_or_gap


class AssemblyPlanValidatorTest(unittest.TestCase):
    def test_shell_rotation_is_validated_without_changing_direction_or_phase(self):
        op = {"op": "SHELL_BOX", "w": 12, "d": 12, "h": 28, "twistTurns": -0.125, "twistPhase": 0.25}
        plan = {"mode": "build", "components": [{
            "component_type": "ASSEMBLY", "params": {"assembly": {"ops": [op]}},
        }]}
        self.assertEqual(plan, finalize_assembly_plan_or_gap(plan, "生成向左旋转的折光匣"))
        for key, value in (("twistTurns", -2.1), ("twistPhase", 1.01), ("twistPhase", False)):
            with self.subTest(key=key, value=value):
                candidate = {"mode": "build", "components": [{
                    "component_type": "ASSEMBLY",
                    "params": {"assembly": {"ops": [{**op, key: value}]}},
                }]}
                result = finalize_assembly_plan_or_gap(candidate, "生成向左旋转的折光匣")
                self.assertEqual("capability_gap", result["plan_status"])
                self.assertEqual("E_SHELL_BOX_TWIST_RANGE", result["capability_gap"]["code"])
                self.assertTrue(result["capability_gap"]["path"].endswith("." + key))

    def test_shell_dimensions_outside_executor_range_become_a_gap(self):
        for key, value in (("w", 4), ("d", 130), ("h", 5), ("h", 256),
                           ("floorStep", 9), ("floorStep", 4.5), ("h", None)):
            with self.subTest(key=key, value=value):
                op = {"op": "SHELL_BOX", "w": 5, "d": 129, "h": 255, "floorStep": 3}
                plan = {"mode": "build", "components": [{
                    "component_type": "ASSEMBLY", "params": {"assembly": {"ops": [op]}},
                }]}
                self.assertEqual([], validate_assembly_plan(plan))
                op[key] = value
                result = finalize_assembly_plan_or_gap(plan, "建造原创折光匣")
                self.assertEqual("capability_gap", result["plan_status"])
                self.assertEqual("E_INT_RANGE", result["capability_gap"]["code"])
                self.assertTrue(result["capability_gap"]["path"].endswith("." + key))
                self.assertEqual(value, op[key])

    def test_logged_linked_buildings_missing_face_is_caught_before_preview(self):
        import json
        from pathlib import Path
        fixture = Path(__file__).resolve().parents[2] / 'src/test/resources/regressions/linked-buildings-missing-face.json'
        issues = validate_assembly_plan(json.loads(fixture.read_text(encoding='utf-8')))
        self.assertEqual(2, sum(i.code == 'E_FACE_MISSING' for i in issues))

    def test_shell_scale_validates_without_changing_the_authored_geometry(self):
        for fields, valid in (
            ({}, True), ({"topScale": 0.85}, True), ({"topScale": 0.49}, False),
            ({"topScale": None}, False), ({"topScale": True}, False),
            ({"topScale": 0.85, "wallMode": "COLONNADE"}, False),
            ({"topScale": 0.85, "openness": 0.65}, False),
            ({"topScale": 1, "wall_mode": "open"}, True),
        ):
            with self.subTest(fields=fields):
                op = {"op": "SHELL_BOX", "w": 12, "d": 12, "h": 28, "twistTurns": 0.125, **fields}
                plan = {"mode": "build", "components": [{
                    "component_type": "ASSEMBLY", "params": {"assembly": {"ops": [op]}},
                }]}
                issues = validate_assembly_plan(plan)
                self.assertEqual([] if valid else ["E_SHELL_BOX_TOP_SCALE"], [i.code for i in issues])
                self.assertEqual({"op": "SHELL_BOX", "w": 12, "d": 12, "h": 28, "twistTurns": 0.125, **fields}, op)
                if not valid:
                    out = finalize_assembly_plan_or_gap(plan, "生成折光匣，不用预设")
                    self.assertEqual("capability_gap", out["plan_status"])
                    self.assertEqual("E_SHELL_BOX_TOP_SCALE", out["capability_gap"]["code"])

    def test_resolve_requires_explicit_preset_opt_in(self):
        self.assertIsNone(resolve_preset_for_intent("原创螺旋瞭望塔"))
        self.assertIsNone(resolve_preset_for_intent("哥特式壳塔"))
        self.assertIsNone(resolve_preset_for_intent("悬索桥 span 40"))
        self.assertIsNone(resolve_preset_for_intent("石拱桥跨河"))
        self.assertIsNone(resolve_preset_for_intent("生成一座桥"))
        self.assertEqual(
            "spiral_watchtower",
            resolve_preset_for_intent("建造一座塔 preset:spiral_watchtower"),
        )
        self.assertEqual(
            "suspension_bridge_simple",
            resolve_preset_for_intent("preset:suspension_bridge_simple span 40"),
        )
        self.assertIsNone(resolve_preset_for_intent("不要使用 preset:spiral_watchtower"))

    def test_auto_injects_only_for_explicit_preset(self):
        empty = {
            "components": [
                {
                    "component_type": "ASSEMBLY",
                    "params": {"assembly": {}},
                }
            ]
        }
        keyword_out = auto_apply_assembly_presets(empty, "螺旋瞭望塔")
        self.assertNotIn("preset", keyword_out["components"][0]["params"]["assembly"])

        explicit_plan = {
            "components": [
                {
                    "component_type": "ASSEMBLY",
                    "params": {"assembly": {}},
                }
            ]
        }
        explicit_out = auto_apply_assembly_presets(
            explicit_plan, "建造一座塔 preset:spiral_watchtower"
        )
        assembly = explicit_out["components"][0]["params"]["assembly"]
        self.assertEqual("spiral_watchtower", assembly.get("preset"))

    def test_request_validation_rejects_a_model_selected_preset(self):
        for request, permitted in (
            ("生成原创折光匣", False),
            ("不要使用 preset:spiral_watchtower", False),
            ("preset:suspension_bridge_simple", False),
            ("请建造 preset:spiral_watchtower", True),
        ):
            with self.subTest(request=request):
                plan = {"mode": "build", "components": [{
                    "component_type": "ASSEMBLY",
                    "params": {"assembly": {"preset": "spiral_watchtower"}},
                }]}
                result = finalize_assembly_plan_or_gap(plan, request)
                if permitted:
                    self.assertNotEqual("capability_gap", result.get("plan_status"))
                    self.assertEqual(plan, result)
                else:
                    self.assertEqual("capability_gap", result.get("plan_status"))
                    self.assertEqual("E_ASSEMBLY_PRESET_NOT_REQUESTED", result["capability_gap"]["code"])
                    self.assertNotIn("spiral_watchtower", str(result["capability_gap"]["suggestions"]))

    def test_incomplete_assembly_without_opt_in_becomes_capability_gap(self):
        plan = {
            "mode": "build",
            "components": [
                {
                    "component_type": "ASSEMBLY",
                    "params": {"assembly": {}},
                }
            ]
        }
        out = finalize_assembly_plan_or_gap(plan, "螺旋瞭望塔")
        self.assertEqual("capability_gap", out.get("plan_status"))
        self.assertNotEqual(
            "spiral_watchtower",
            (out.get("components") or [{}])[0].get("params", {}).get("assembly", {}).get("preset"),
        )
        issues = validate_assembly_plan(plan)
        self.assertTrue(any(i.code == "E_ASSEMBLY_EMPTY" for i in issues))

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

    def test_capability_gap_plan_fields_validate(self):
        from app.models.llm_plan import validate_llm_plan_dict

        plan = validate_llm_plan_dict({
            "mode": "build",
            "anchor": {"x": 0, "y": 64, "z": 0},
            "plan_status": "capability_gap",
            "error": "unsupported freeform geometry",
            "capability_gap": {
                "code": "E_UNSUPPORTED_GEOMETRY",
                "message": "unsupported freeform geometry",
                "path": "plan",
                "suggestions": ["use preset spiral_watchtower"],
            },
        })
        self.assertEqual("capability_gap", plan.get("plan_status"))

    def test_format_repair_prompt_includes_errors(self):
        plan = {"components": []}
        from app.services.assembly_plan_validator import AssemblyPlanIssue

        text = format_repair_prompt(
            plan,
            [AssemblyPlanIssue("x", "E_TEST", "boom")],
        )
        self.assertIn("E_TEST", text)
        self.assertIn("only if the user explicitly requested", text)
        self.assertNotIn("spiral_watchtower", text)


if __name__ == "__main__":
    unittest.main()
