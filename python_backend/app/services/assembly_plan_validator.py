"""
MetaAssembly / ASSEMBLY plan validation (Python mirror of Java AssemblyGraphDslValidator + preset checks).
Used by generate_llm_plan() repair loop and golden_eval.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Set, Tuple

from app.services.assembly_schema_loader import (
    assembly_component_catalog,
    compatibility_rules,
    component_ports,
    known_preset_ids,
    load_assembly_schema,
)

KNOWN_PRESETS = known_preset_ids()

ASSEMBLY_INTENT_MARKERS = (
    "assembly", "螺旋", "spiral", "helix", "twist", "瞭望", "watchtower",
    "自由几何", "freeform", "不要地标",
    # Suspension-only bridge cues — bare 桥/bridge must NOT force assembly presets
    # (stone/wood/arch bridges are compositional LLM plans, not suspension_bridge_simple).
    "悬索", "suspension", "吊桥", "斜拉", "cable-stayed",
)

BUILTIN_PORTS = frozenset({
    "center", "bottom_center", "top_center", "bottom", "top", "mid",
    "north", "south", "east", "west", "nw", "ne", "sw", "se",
    "entrance", "exit", "in", "out",
    "front_left", "front_right", "back_left", "back_right",
})


@dataclass(frozen=True)
class AssemblyPlanIssue:
    path: str
    code: str
    message: str
    severity: str = "ERROR"


def detects_assembly_intent(text: Optional[str]) -> bool:
    if not text or not str(text).strip():
        return False
    lower = str(text).lower()
    return any(m.lower() in lower for m in ASSEMBLY_INTENT_MARKERS)


def resolve_preset_for_intent(text: Optional[str]) -> Optional[str]:
    """Return a registered ASSEMBLY preset only for explicit user opt-in ``preset:<id>``.

    Aesthetic keywords (spiral / gothic / suspension / …) must NOT select a fixed
    executor. Incomplete ASSEMBLY plans go through LLM repair or capability_gap.
    Negations such as "不要使用 preset:…" never authorize injection.
    """
    if not isinstance(text, str):
        return None
    lowered = text.strip().lower()
    if not lowered:
        return None
    if re.search(
        r"(?:不要(?:使用|用)?|不使用|不用|禁用|关闭)\s*preset\s*:|do\s+not\s+use\s+preset\s*:",
        lowered,
    ):
        return None
    explicit = re.search(r"(?:^|\s)preset:([a-z0-9_]+)(?=$|\s|[,.，。])", lowered)
    if not explicit:
        return None
    preset_id = explicit.group(1)
    if preset_id in KNOWN_PRESETS:
        return preset_id
    return None


def _assembly_payload(params: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    assembly = params.get("assembly")
    if isinstance(assembly, dict):
        if _has_geometry(assembly) or not _has_geometry(params):
            return assembly
        return {key: value for key, value in params.items() if key != "assembly"}
    if any(k in params for k in ("graph", "ops", "macro", "preset", "presetId")):
        return params
    return None


def _has_geometry(payload: Dict[str, Any]) -> bool:
    if payload.get("preset") or payload.get("presetId"):
        return True
    if payload.get("ops"):
        return True
    graph = payload.get("graph")
    if isinstance(graph, dict) and graph.get("components"):
        return True
    if payload.get("components"):
        return True
    return False


def auto_apply_assembly_presets(plan: Dict[str, Any], user_text: Optional[str]) -> Dict[str, Any]:
    """Inject preset only for explicit ``preset:<id>`` when ASSEMBLY geometry is missing.

    Does not guess aesthetics from keywords. LLM-authored preset/graph/ops/fbd are left intact.
    """
    if not isinstance(plan, dict):
        return plan
    preset_id = resolve_preset_for_intent(user_text)
    if not preset_id:
        return plan
    comps = plan.get("components")
    if not isinstance(comps, list):
        return plan
    for comp in comps:
        if not isinstance(comp, dict):
            continue
        if str(comp.get("component_type") or "").upper() != "ASSEMBLY":
            continue
        params = comp.get("params")
        if not isinstance(params, dict):
            params = {}
            comp["params"] = params
        if 'fbd_program' in params:
            continue  # Procedural authority must never acquire a guessed preset.
        payload = _assembly_payload(params)
        if payload is None:
            payload = {}
            params["assembly"] = payload
        if _has_geometry(payload):
            continue
        payload["preset"] = preset_id
        payload.setdefault("presetParams", {})
    return plan


def validate_assembly_plan(plan: Dict[str, Any], *, allowed_presets: set[str] | None = None) -> List[AssemblyPlanIssue]:
    issues: List[AssemblyPlanIssue] = []
    if not isinstance(plan, dict):
        return issues
    comps = plan.get("components")
    if not isinstance(comps, list):
        return issues
    for i, comp in enumerate(comps):
        if not isinstance(comp, dict):
            continue
        ctype = str(comp.get("component_type") or "").upper()
        if ctype == "ASSEMBLY":
            _validate_assembly_component(issues, comp, i, allowed_presets)
        elif ctype.startswith("MASS_") or ctype in ("MASS_MAIN", "MAIN_MASS"):
            params = comp.get("params") if isinstance(comp.get("params"), dict) else {}
            if isinstance(params.get("assembly"), dict):
                issues.append(AssemblyPlanIssue(
                    f"components[{i}].params.assembly",
                    "E_NESTED_ASSEMBLY_IN_MASS",
                    f"{ctype} must not nest params.assembly; use top-level ASSEMBLY component",
                ))
    return issues


def _validate_assembly_component(
    issues: List[AssemblyPlanIssue], comp: Dict[str, Any], idx: int, allowed_presets: set[str] | None,
) -> None:
    params = comp.get("params") if isinstance(comp.get("params"), dict) else {}
    if 'fbd_program' in params:
        from ..models.llm_plan import ComponentModel
        from pydantic import ValidationError
        try:
            ComponentModel.model_validate(comp)
        except ValidationError as exc:
            issues.append(AssemblyPlanIssue(f'components[{idx}].params.fbd_program',
                                            'E_FBD_ENVELOPE', str(exc)))
        return  # Java validates this AST; the legacy Assembly DSL validator must not reinterpret it.
    payload = _assembly_payload(params)
    base = f"components[{idx}]"
    if payload is None:
        issues.append(AssemblyPlanIssue(
            f"{base}.params.assembly",
            "E_ASSEMBLY_MISSING",
            "ASSEMBLY component requires params.assembly, preset, or graph/ops",
        ))
        return
    preset = str(payload.get("preset") or payload.get("presetId") or "").strip()
    if preset:
        if preset not in KNOWN_PRESETS:
            issues.append(AssemblyPlanIssue(
                f"{base}.params.assembly.preset",
                "E_UNKNOWN_PRESET",
                f"unknown preset: {preset}",
            ))
        elif allowed_presets is not None and preset not in allowed_presets:
            issues.append(AssemblyPlanIssue(
                f"{base}.params.assembly.preset",
                "E_ASSEMBLY_PRESET_NOT_REQUESTED",
                "Use model-authored graph or ops unless the user explicitly requested this preset.",
            ))
        return
    engine_ops = load_assembly_schema().get("ops") or []
    ops = payload.get("ops", [])
    if not isinstance(ops, list):
        issues.append(AssemblyPlanIssue(f"{base}.params.assembly.ops", "E_OPS_NOT_ARRAY",
                                       "Assembly ops must be an array"))
        return
    for j, op in enumerate(ops):
        path = f"{base}.params.assembly.ops[{j}]"
        if not isinstance(op, dict):
            issues.append(AssemblyPlanIssue(path, "E_OP_NOT_OBJECT", "Assembly op must be an object"))
            continue
        kind = str(op.get("op") or "").strip().upper()
        if not kind:
            issues.append(AssemblyPlanIssue(path + ".op", "E_OP_MISSING", "Assembly op requires an op field"))
            continue
        if engine_ops and kind not in engine_ops:
            issues.append(AssemblyPlanIssue(path + ".op", "E_OP_UNSUPPORTED", f"Unsupported Assembly op: {kind}"))
            continue
        _validate_geometry_fields(issues, op, path, kind)
    graph = payload.get("graph")
    graph_path = f"{base}.params.assembly.graph"
    if "graph" in payload and not isinstance(graph, dict):
        issues.append(AssemblyPlanIssue(graph_path, "E_GRAPH_NOT_OBJECT", "Assembly graph must be an object"))
        return
    if graph is not None:
        for key in ("components", "connections"):
            if key in payload and payload[key] != []:
                issues.append(AssemblyPlanIssue(f"{base}.params.assembly.{key}", "E_ASSEMBLY_GRAPH_LOCATION",
                                               "Keep all graph components and connections together in one location"))
    if graph is None and ("components" in payload or "connections" in payload):
        graph = payload
        graph_path = f"{base}.params.assembly"
    if graph is None and not payload.get("ops"):
        issues.append(AssemblyPlanIssue(
            f"{base}.params.assembly",
            "E_ASSEMBLY_EMPTY",
            "ASSEMBLY missing preset, graph.components, or ops",
        ))
        return
    if not isinstance(graph, dict):
        return
    components = graph.get("components")
    connections = graph.get("connections", [])
    if not isinstance(connections, list):
        issues.append(AssemblyPlanIssue(graph_path + ".connections", "E_CONNECTIONS_NOT_ARRAY",
                                       "Assembly connections must be an array"))
    if "components" in graph and not isinstance(components, list):
        issues.append(AssemblyPlanIssue(graph_path + ".components", "E_COMPONENTS_NOT_ARRAY",
                                       "Assembly components must be an array"))
        return
    if not components:
        if not payload.get("ops"):
            issues.append(AssemblyPlanIssue(
                graph_path + ".components",
                "E_ASSEMBLY_EMPTY",
                "graph.components must be non-empty when no ops[] present",
            ))
        return
    by_id: Dict[str, Dict[str, Any]] = {}
    catalog = assembly_component_catalog()
    issue_count = len(issues)
    for j, c in enumerate(components):
        path = f"{graph_path}.components[{j}]"
        if not isinstance(c, dict):
            issues.append(AssemblyPlanIssue(path, "E_COMPONENT_NOT_OBJECT", "Assembly component must be an object"))
            continue
        cid = str(c.get("id") or "").strip()
        if cid:
            if cid in by_id:
                issues.append(AssemblyPlanIssue(
                    path + ".id",
                    "E_DUP_COMPONENT_ID",
                    f"duplicate component id: {cid}",
                ))
            by_id[cid] = c
        ctype = str(c.get("type") or "").strip().upper() or str(c.get("op") or "").strip().upper()
        if not ctype:
            issues.append(AssemblyPlanIssue(
                path + ".type",
                "E_COMPONENT_TYPE_MISSING",
                "component missing type",
            ))
            continue
        definition = catalog.get(ctype)
        if catalog and definition is None:
            issues.append(AssemblyPlanIssue(path + ".type", "E_COMPONENT_TYPE_UNSUPPORTED",
                                           f"Unsupported Assembly component type: {ctype}"))
            continue
        kind = definition["type"] if definition is not None else ctype
        if kind == "BOX_SHELL":
            kind = "SHELL_BOX"
        _validate_geometry_fields(issues, c, path, kind, graph_component=True)
    if ops and len(issues) == issue_count:
        issues.append(AssemblyPlanIssue(graph_path + ".components", "E_ASSEMBLY_MIXED_GEOMETRY",
                                       "Combine the intended geometry in either ops or graph components"))
    if not isinstance(connections, list):
        return
    for k, conn in enumerate(connections):
        cp = f"{graph_path}.connections[{k}]"
        if not isinstance(conn, dict):
            issues.append(AssemblyPlanIssue(cp, "E_CONN_NOT_OBJECT", "Assembly connection must be an object"))
            continue
        _validate_endpoint(issues, cp + ".from", conn.get("from"), by_id, "from")
        _validate_endpoint(issues, cp + ".to", conn.get("to"), by_id, "to")


def _validate_geometry_fields(
    issues: List[AssemblyPlanIssue], comp: dict, path: str, kind: str, *, graph_component: bool = False,
) -> None:
    if kind == "BEZIER_SURFACE" and not graph_component:
        points = comp.get("points")
        if isinstance(points, list) and len(points) == 4 and all(
            isinstance(row, list) and len(row) == 4 for row in points
        ):
            points = [point for row in points for point in row]
        if not isinstance(points, list) or len(points) != 16 or any(
            not isinstance(point, dict) or any(type(point.get(axis)) is not int for axis in ("x", "y", "z"))
            for point in points
        ):
            issues.append(AssemblyPlanIssue(path + ".points", "E_BEZIER_POINTS_INVALID",
                                           "Provide 16 integer x/y/z control points or a 4×4 grid"))
        for key, alias, minimum, maximum in (("uSamples", "u", 2, 512), ("vSamples", "v", 2, 512),
                                              ("thickness", "thickness", 1, 9)):
            field = key if comp.get(key) is not None else alias
            value = comp.get(field)
            if value is not None and (type(value) is not int or not minimum <= value <= maximum):
                issues.append(AssemblyPlanIssue(f"{path}.{field}", "E_INT_RANGE",
                                               f"{field} must be an integer in {minimum}..{maximum}"))
        if comp.get("connectSamples") is not None and type(comp["connectSamples"]) is not bool:
            issues.append(AssemblyPlanIssue(path + ".connectSamples", "E_BOOL_TYPE",
                                           "connectSamples must be a boolean"))
    if kind == "REVOLVE_SURFACE" and not graph_component:
        field = next((key for key in ("profileRings", "rings", "profilePoints", "points")
                      if comp.get(key) is not None), "profilePoints")
        profile = comp.get(field)
        if isinstance(profile, list) and len(profile) == 1 and isinstance(profile[0], list):
            profile = profile[0]
        if not isinstance(profile, list) or len(profile) < 2 or any(
            not isinstance(point, dict) or type(point.get("x")) is not int
            or point["x"] < 0 or type(point.get("y")) is not int
            for point in profile
        ):
            issues.append(AssemblyPlanIssue(f"{path}.{field}", "E_REVOLVE_PROFILE_INVALID",
                                           "Provide one ordered profile of at least two integer radius/height points"))
        for key, minimum, maximum in (("segments", 8, 512), ("thickness", 1, 9)):
            value = comp.get(key)
            if value is not None and (type(value) is not int or not minimum <= value <= maximum):
                issues.append(AssemblyPlanIssue(f"{path}.{key}", "E_INT_RANGE",
                                               f"{key} must be an integer in {minimum}..{maximum}"))
        angle_key = "angleDeg" if comp.get("angleDeg") is not None else "angle"
        angle = comp.get(angle_key)
        if angle is not None and (type(angle) not in (int, float) or not 0 < angle <= 360):
            issues.append(AssemblyPlanIssue(f"{path}.{angle_key}", "E_REVOLVE_ANGLE_RANGE",
                                           "Rotation angle must be greater than 0 and at most 360 degrees"))
        if comp.get("connectSamples") is not None and type(comp["connectSamples"]) is not bool:
            issues.append(AssemblyPlanIssue(f"{path}.connectSamples", "E_BOOL_TYPE",
                                           "connectSamples must be a boolean"))
    if kind == "LOFT_SURFACE":
        sections = comp.get("sections")
        if not isinstance(sections, list) or len(sections) < 2:
            issues.append(AssemblyPlanIssue(path + ".sections", "E_LOFT_SECTIONS",
                                           "LOFT_SURFACE requires at least two sections"))
            return
        point_count = None
        for index, section in enumerate(sections):
            section_path = f"{path}.sections[{index}]"
            if not isinstance(section, dict):
                issues.append(AssemblyPlanIssue(section_path, "E_LOFT_SECTION_NOT_OBJECT",
                                               "Loft section must be an object"))
                continue
            field = next((key for key in ("profileRings", "rings", "profilePoints")
                          if section.get(key) is not None), "profilePoints")
            profile = section.get(field)
            if isinstance(profile, list) and len(profile) == 1 and isinstance(profile[0], list):
                profile = profile[0]
            if not isinstance(profile, list) or len(profile) < 2 or any(
                not isinstance(point, dict) or any(type(point.get(axis)) is not int for axis in ("x", "y"))
                for point in profile
            ):
                issues.append(AssemblyPlanIssue(f"{section_path}.{field}", "E_LOFT_PROFILE_INVALID",
                                               "Provide one profile of at least two integer x/y points"))
            elif point_count is None:
                point_count = len(profile)
            elif len(profile) != point_count:
                issues.append(AssemblyPlanIssue(f"{section_path}.{field}", "E_LOFT_PROFILE_COUNT",
                                               f"Expected {point_count} profile points, got {len(profile)}"))
    if kind == "OPENINGS" and not comp.get("face") and not comp.get("faces"):
        issues.append(AssemblyPlanIssue(path + '.face', "E_FACE_MISSING",
                                       "OPENINGS requires face/faces; side is not an executable face field"))
    if kind == "SHELL_BOX":
        dimensions = (("w", "width", 5, 129), ("d", "depth", 5, 129),
                      ("h", "height", 6, 255), ("floorStep", "floorStep", 3, 8))
        for short, long, minimum, maximum in dimensions:
            key = long if graph_component and short not in comp else short
            if key not in comp:
                continue
            value = comp[key]
            if type(value) not in (int, float) or not minimum <= value <= maximum or int(value) != value:
                issues.append(AssemblyPlanIssue(f"{path}.{key}", "E_INT_RANGE",
                                               f"SHELL_BOX {key} must be an integer in {minimum}..{maximum}"))
        for key, minimum, maximum in (("twistTurns", -2, 2), ("twistPhase", 0, 1)):
            if key not in comp:
                continue
            value = comp[key]
            if type(value) not in (int, float) or not minimum <= value <= maximum:
                issues.append(AssemblyPlanIssue(f"{path}.{key}", "E_SHELL_BOX_TWIST_RANGE",
                                               f"SHELL_BOX {key} must be a number in {minimum}..{maximum} turns"))
        if "topScale" in comp:
            scale = comp["topScale"]
            if type(scale) not in (int, float) or not 0.5 <= scale <= 1:
                issues.append(AssemblyPlanIssue(path + ".topScale", "E_SHELL_BOX_TOP_SCALE",
                                               "topScale must be a number in 0.5..1.0"))
            else:
                wall_mode = str(comp.get("wallMode", comp.get("wall_mode", ""))).strip().upper()
                openness = comp.get("openness")
                open_walls = wall_mode in {"COLONNADE", "OPEN", "PILLARS", "POSTS"} or (
                    type(openness) in (int, float) and openness >= 0.65
                )
                if open_walls and scale != 1:
                    issues.append(AssemblyPlanIssue(path + ".topScale", "E_SHELL_BOX_TOP_SCALE",
                                                   "Open walls require topScale=1 or omission"))
    if kind == "STAIR_SYSTEM":
        a, b = comp.get("from"), comp.get("to")
        if not all(isinstance(p, dict) and all(isinstance(p.get(k),int) and not isinstance(p.get(k),bool) for k in ('x','y','z')) for p in (a,b)):
            issues.append(AssemblyPlanIssue(path, "E_STAIR_FLIGHT_INVALID", "from/to must contain integer x/y/z")); return
        dx, dy, dz = b['x']-a['x'], b['y']-a['y'], b['z']-a['z']
        if (dx and dz) or abs(dy) > max(abs(dx),abs(dz)) or max(abs(dx),abs(dz)) > 4096:
            issues.append(AssemblyPlanIssue(path, "E_STAIR_FLIGHT_INVALID", "Use axis-aligned flights with run >= rise and run <= 4096"))


def _validate_endpoint(
    issues: List[AssemblyPlanIssue],
    path: str,
    endpoint: Any,
    by_id: Dict[str, Dict[str, Any]],
    role: str,
) -> None:
    parsed = _parse_endpoint(endpoint)
    if parsed is None:
        issues.append(AssemblyPlanIssue(path, "E_ENDPOINT_COMPONENT_REQUIRED",
                                       f"{role} must reference an Assembly component"))
        return
    comp_id, port = parsed
    if comp_id and comp_id not in by_id:
        issues.append(AssemblyPlanIssue(
            path,
            "E_CONN_UNKNOWN_COMPONENT",
            f"{role} references unknown component id: {comp_id}",
        ))
        return
    if not comp_id or not port:
        return
    comp = by_id.get(comp_id)
    if comp is None:
        return
    ports = _ports_for_component(comp)
    if _normalize_port_id(port) not in ports:
        issues.append(AssemblyPlanIssue(
            path,
            "E_CONN_UNKNOWN_PORT",
            f"{role} port {comp_id}.{port} not found (available: {', '.join(sorted(ports))})",
        ))


def _parse_endpoint(endpoint: Any) -> Optional[Tuple[Optional[str], Optional[str]]]:
    if endpoint is None:
        return None
    if isinstance(endpoint, str):
        text = endpoint.strip()
        if not text:
            return None
        if "." in text:
            comp_id, port = text.split(".", 1)
            return comp_id.strip(), port.strip()
        return text, None
    if isinstance(endpoint, dict):
        comp_id = str(endpoint.get("component") or "").strip() or str(endpoint.get("id") or "").strip()
        port = endpoint.get("port")
        if comp_id:
            return comp_id, str(port).strip() if port else None
    return None


def _normalize_port_id(name: str) -> str:
    key = name.strip().lower().replace("-", "_").replace(" ", "_") or "center"
    aliases = {
        "n": "north", "s": "south", "e": "east", "w": "west",
        "topcenter": "top_center", "bottomcenter": "bottom_center",
        "frontleft": "front_left", "frontright": "front_right",
        "backleft": "back_left", "backright": "back_right",
    }
    return aliases.get(key, key)


def _ports_for_component(comp: Dict[str, Any]) -> Set[str]:
    ctype = str(comp.get("type") or "").strip() or str(comp.get("op") or "").strip()
    ports = {_normalize_port_id(port) for port in component_ports(ctype) or BUILTIN_PORTS}
    # The exported shell sample has dimensions, but an unsized shell exposes only axial ports.
    if ctype.upper() in ("SHELL_BOX", "BOX_SHELL") and not {"w", "d", "width", "depth"}.intersection(comp):
        ports.intersection_update({"center", "bottom_center", "top_center", "bottom", "top", "mid"})
    custom_ports = comp.get("ports")
    if isinstance(custom_ports, dict):
        ports.update(_normalize_port_id(name) for name, point in custom_ports.items()
                     if isinstance(name, str) and isinstance(point, dict))
    return ports


def format_repair_prompt(plan: Dict[str, Any], issues: List[AssemblyPlanIssue]) -> str:
    errors = [i for i in issues if i.severity == "ERROR"]
    lines = [
        "Your previous LlmPlan JSON failed MetaAssembly validation. Fix ONLY the assembly-related errors.",
        "Return the FULL corrected LlmPlan JSON (valid JSON only, no markdown).",
        "",
        "Errors:",
    ]
    for e in errors[:12]:
        lines.append(f"- [{e.code}] {e.path}: {e.message}")
    lines.extend([
        "",
        "Rules:",
        '- Use component_type="ASSEMBLY" at top level (never nest params.assembly inside MASS_*).',
        "- Preserve the requested geometry and valid model-authored parameters. Use a preset only if the user explicitly requested its ID.",
        "- Keep the Assembly geometry in the repaired plan. If it cannot be represented, return capability_gap.",
        "- Preserve the plan mode, anchor, layout, global constraints, style profile and style attributes.",
        '- Connection endpoints must be "ComponentId.port" using valid ports (center, top_center, bottom_center, north, ...).',
        '- If geometry is unsupported, return plan_status="capability_gap" with capability_gap{code,message,path,suggestions}.',
    ])
    for rule in compatibility_rules()[:4]:
        lines.append(f"- {rule}")
    lines.extend([
        "",
        "Previous plan JSON:",
    ])
    import json
    lines.append(json.dumps(plan, ensure_ascii=False))
    return "\n".join(lines)
