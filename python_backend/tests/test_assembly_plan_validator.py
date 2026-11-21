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
    def test_loft_requires_matching_profiles_without_rewriting_points(self):
        first = [{"x": 0, "y": 2}, {"x": 8, "y": 2}]
        second = [{"x": 2, "y": 6}, {"x": 4, "y": 7}, {"x": 6, "y": 6}]
        loft = {"type": "LOFT", "sections": [{"profilePoints": first}, {"profilePoints": second}]}
        plan = {"components": [{"component_type": "ASSEMBLY", "params": {
            "assembly": {"graph": {"components": [loft]}},
        }}]}
        gap = finalize_assembly_plan_or_gap(plan, "建造折光匣斜面")["capability_gap"]
        self.assertEqual("E_LOFT_PROFILE_COUNT", gap["code"])
        self.assertEqual("Expected 2 profile points, got 3", gap["message"])
        self.assertTrue(gap["path"].endswith("sections[1].profilePoints"))
        self.assertEqual(3, len(second))
        second.pop(1)
        self.assertEqual([], validate_assembly_plan(plan))

    def test_graph_shell_dimension_alias_is_validated_at_its_authored_path(self):
        shell = {"type": "BOX_SHELL", "width": 4, "depth": 10, "height": 28}
        plan = {"components": [{"component_type": "ASSEMBLY", "params": {"assembly": {"graph": {
            "components": [shell],
        }}}}]}
        issues = validate_assembly_plan(plan)
        self.assertEqual(["E_INT_RANGE"], [issue.code for issue in issues])
        self.assertTrue(issues[0].path.endswith("components[0].width"))
        shell["w"] = 12
        self.assertEqual([], validate_assembly_plan(plan))
        self.assertEqual(4, shell["width"])

    def test_shell_without_dimensions_only_exposes_axial_and_custom_ports(self):
        shell = {"id": "shell", "type": "BOX_SHELL"}
        connection = {"from": "shell.N", "to": "shell.top-center"}
        plan = {"components": [{"component_type": "ASSEMBLY", "params": {"assembly": {"graph": {
            "components": [shell], "connections": [connection],
        }}}}]}
        issues = validate_assembly_plan(plan)
        self.assertEqual(["E_CONN_UNKNOWN_PORT"], [issue.code for issue in issues])
        self.assertTrue(issues[0].path.endswith("connections[0].from"))
        shell["ports"] = {"north": {"z": -3}}
        self.assertEqual([], validate_assembly_plan(plan))
        shell.pop("ports")
        shell.update(w=12, d=12)
        self.assertEqual([], validate_assembly_plan(plan))

    def test_existing_port_spellings_and_custom_ports_resolve(self):
        component = {"id": "shell", "type": "SHELL_BOX", "w": 12, "d": 12, "h": 28,
                     "ports": {"Canopy Mount": {"x": 1}}}
        connections = [{"from": "shell.N", "to": "shell.top-center"},
                       {"from": "shell.canopy-mount", "to": "shell.center"}]
        payload = {"graph": {"components": [component], "connections": connections}}
        plan = {"components": [{"component_type": "ASSEMBLY", "params": {"assembly": payload}}]}
        self.assertEqual([], validate_assembly_plan(plan))
        self.assertEqual("shell.canopy-mount", connections[1]["from"])
        self.assertEqual({"x": 1}, component["ports"]["Canopy Mount"])

    def test_connection_requires_a_component_but_accepts_relative_offsets(self):
        connection = {"from": {"x": 3, "y": 0, "z": 0}, "to": "shell.center"}
        payload = {"graph": {"components": [{"id": "shell", "type": "SHELL_BOX"}],
                             "connections": [connection]}}
        plan = {"components": [{"component_type": "ASSEMBLY", "params": {"assembly": payload}}]}
        issues = validate_assembly_plan(plan)
        self.assertEqual(["E_ENDPOINT_COMPONENT_REQUIRED"], [issue.code for issue in issues])
        self.assertTrue(issues[0].path.endswith("connections[0].from"))
        connection["from"] = {"component": "shell", "port": "missing_port", "x": 3}
        self.assertEqual(["E_CONN_UNKNOWN_PORT"], [issue.code for issue in validate_assembly_plan(plan)])
        connection["from"]["port"] = "center"
        self.assertEqual([], validate_assembly_plan(plan))
        self.assertEqual(3, connection["from"]["x"])

    def test_nested_graph_cannot_mask_top_level_connections(self):
        payload = {"graph": {"components": [{"id": "shell", "type": "SHELL_BOX"}]},
                   "connections": [{"from": "shell.center", "to": "shell.top_center"}]}
        plan = {"mode": "build", "components": [{
            "component_type": "ASSEMBLY", "params": {"assembly": payload},
        }]}
        gap = finalize_assembly_plan_or_gap(plan, "生成原创折光匣")["capability_gap"]
        self.assertEqual("E_ASSEMBLY_GRAPH_LOCATION", gap["code"])
        self.assertEqual("components[0].params.assembly.connections", gap["path"])
        payload["connections"] = []
        self.assertEqual([], validate_assembly_plan(plan))

    def test_mixed_geometry_reports_the_ignored_component_list(self):
        payload = {"ops": [{"op": "SHELL_BOX"}], "graph": {
            "components": [{"id": "offset", "type": "OFFSET_SURFACE", "source": {}}],
        }}
        plan = {"mode": "build", "components": [{
            "component_type": "ASSEMBLY", "params": {"assembly": payload},
        }]}
        gap = finalize_assembly_plan_or_gap(plan, "生成原创折光匣")["capability_gap"]
        self.assertEqual("E_ASSEMBLY_MIXED_GEOMETRY", gap["code"])
        self.assertTrue(gap["path"].endswith(".graph.components"))
        self.assertEqual([], validate_assembly_plan({"components": [{
            "component_type": "ASSEMBLY", "params": {"assembly": {**payload, "ops": []}},
        }]}))

    def test_executable_ops_cannot_mask_malformed_graph_components(self):
        for graph_fields, code, suffix in (
            ({"graph": []}, "E_GRAPH_NOT_OBJECT", ".graph"),
            ({"graph": {"components": {}}}, "E_COMPONENTS_NOT_ARRAY", ".graph.components"),
            ({"components": None}, "E_COMPONENTS_NOT_ARRAY", ".components"),
            ({"graph": {"components": ["shell"]}}, "E_COMPONENT_NOT_OBJECT", ".graph.components[0]"),
        ):
            with self.subTest(graph_fields=graph_fields):
                payload = {"ops": [{"op": "SHELL_BOX"}], **graph_fields}
                plan = {"components": [{"component_type": "ASSEMBLY", "params": {"assembly": payload}}]}
                issues = validate_assembly_plan(plan)
                self.assertEqual([code], [issue.code for issue in issues])
                self.assertEqual("components[0].params.assembly" + suffix, issues[0].path)

    def test_malformed_graph_connections_report_the_authored_path(self):
        for nested in (False, True):
            for connections, code, suffix in (
                ({}, "E_CONNECTIONS_NOT_ARRAY", ".connections"),
                (["shell.center"], "E_CONN_NOT_OBJECT", ".connections[0]"),
            ):
                with self.subTest(nested=nested, connections=connections):
                    graph = {"components": [{"id": "shell", "type": "SHELL_BOX"}],
                             "connections": connections}
                    payload = {"graph": graph} if nested else graph
                    plan = {"mode": "build", "components": [{
                        "component_type": "ASSEMBLY", "params": {"assembly": payload},
                    }]}
                    gap = finalize_assembly_plan_or_gap(plan, "生成原创折光匣")["capability_gap"]
                    self.assertEqual(code, gap["code"])
                    prefix = "components[0].params.assembly" + (".graph" if nested else "")
                    self.assertEqual(prefix + suffix, gap["path"])

    def test_graph_repair_reports_unknown_type_with_existing_aliases_intact(self):
        components = [
            {"id": "shell", "type": "BOX_SHELL", "w": 12, "d": 12, "h": 28},
            {"id": "offset", "type": "OFFSET_SURFACE", "source": {}},
            {"id": "unknown", "type": "CUSTOM_SHELL_BOX"},
        ]
        plan = {"mode": "build", "components": [{
            "component_type": "ASSEMBLY", "params": {"assembly": {"graph": {"components": components}}},
        }]}
        result = finalize_assembly_plan_or_gap(plan, "生成原创折光匣")
        self.assertEqual("capability_gap", result["plan_status"])
        self.assertEqual("E_COMPONENT_TYPE_UNSUPPORTED", result["capability_gap"]["code"])
        self.assertTrue(result["capability_gap"]["path"].endswith("components[2].type"))
        self.assertEqual("BOX_SHELL", components[0]["type"])
        self.assertEqual("OFFSET_SURFACE", components[1]["type"])

    def test_unknown_operation_blocks_a_partial_build(self):
        plan = {"mode": "build", "components": [{
            "component_type": "ASSEMBLY",
            "params": {"assembly": {"ops": [{"op": "SHELL_BOX"}, {"op": "NOVEL_UNSUPPORTED_OP"}]}},
        }]}
        result = finalize_assembly_plan_or_gap(plan, "生成原创折光匣")
        self.assertEqual("capability_gap", result["plan_status"])
        self.assertEqual("E_OP_UNSUPPORTED", result["capability_gap"]["code"])
        self.assertTrue(result["capability_gap"]["path"].endswith("ops[1].op"))

    def test_shell_geometry_uses_the_executors_operation_selector(self):
        for payload, path_suffix in (
            ({"ops": [{"op": "SHELL_BOX", "type": "OTHER", "w": 4}]}, "ops[0].w"),
            ({"graph": {"components": [{"id": "shell", "type": "BOX_SHELL", "w": 4}]}},
             "graph.components[0].w"),
            ({"graph": {"components": [{"id": "shell", "type": " ", "op": "SHELL_BOX", "w": 4}]}},
             "graph.components[0].w"),
        ):
            with self.subTest(path=path_suffix):
                plan = {"components": [{"component_type": "ASSEMBLY", "params": {"assembly": payload}}]}
                issues = validate_assembly_plan(plan)
                self.assertEqual(["E_INT_RANGE"], [issue.code for issue in issues])
                self.assertTrue(issues[0].path.endswith(path_suffix))

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
