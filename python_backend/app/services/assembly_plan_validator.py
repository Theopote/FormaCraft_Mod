"""
MetaAssembly / ASSEMBLY plan validation (Python mirror of Java AssemblyGraphDslValidator + preset checks).
Used by generate_llm_plan() repair loop and golden_eval.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Set, Tuple

KNOWN_PRESETS = frozenset({
    "spiral_watchtower",
    "suspension_bridge_simple",
    "gothic_shell_box",
})

ASSEMBLY_INTENT_MARKERS = (
    "assembly", "螺旋", "spiral", "helix", "twist", "瞭望", "watchtower",
    "自由几何", "freeform", "不要地标", "bridge", "悬索", "suspension", "桥",
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
    if not text:
        return None
    lower = str(text).lower()
    if any(k in lower for k in ("桥", "bridge", "悬索", "suspension", "cable-stayed", "斜拉")):
        return "suspension_bridge_simple"
    if any(k in lower for k in ("螺旋", "spiral", "helix", "twist", "瞭望", "watchtower", "lookout")):
        return "spiral_watchtower"
    if any(k in lower for k in ("哥特", "gothic", "shell", "壳")):
        return "gothic_shell_box"
    if detects_assembly_intent(text):
        return "spiral_watchtower"
    return None


def _assembly_payload(params: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    assembly = params.get("assembly")
    if isinstance(assembly, dict):
        return assembly
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
    """Inject preset shorthand when ASSEMBLY intent is clear but LLM omitted graph/preset."""
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
        payload = _assembly_payload(params)
        if payload is None:
            payload = {}
            params["assembly"] = payload
        if _has_geometry(payload):
            continue
        payload["preset"] = preset_id
        payload.setdefault("presetParams", {})
    return plan


def validate_assembly_plan(plan: Dict[str, Any]) -> List[AssemblyPlanIssue]:
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
            _validate_assembly_component(issues, comp, i)
        elif ctype.startswith("MASS_") or ctype in ("MASS_MAIN", "MAIN_MASS"):
            params = comp.get("params") if isinstance(comp.get("params"), dict) else {}
            if isinstance(params.get("assembly"), dict):
                issues.append(AssemblyPlanIssue(
                    f"components[{i}].params.assembly",
                    "E_NESTED_ASSEMBLY_IN_MASS",
                    f"{ctype} must not nest params.assembly; use top-level ASSEMBLY component",
                ))
    return issues


def _validate_assembly_component(issues: List[AssemblyPlanIssue], comp: Dict[str, Any], idx: int) -> None:
    params = comp.get("params") if isinstance(comp.get("params"), dict) else {}
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
        return
    graph = payload.get("graph") if isinstance(payload.get("graph"), dict) else None
    if graph is None and payload.get("components"):
        graph = {"components": payload.get("components"), "connections": payload.get("connections", [])}
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
    connections = graph.get("connections")
    if not isinstance(components, list) or not components:
        if not payload.get("ops"):
            issues.append(AssemblyPlanIssue(
                f"{base}.params.assembly.graph.components",
                "E_ASSEMBLY_EMPTY",
                "graph.components must be non-empty when no ops[] present",
            ))
        return
    by_id: Dict[str, Dict[str, Any]] = {}
    for j, c in enumerate(components):
        if not isinstance(c, dict):
            continue
        cid = str(c.get("id") or "").strip()
        if cid:
            if cid in by_id:
                issues.append(AssemblyPlanIssue(
                    f"{base}.params.assembly.graph.components[{j}].id",
                    "E_DUP_COMPONENT_ID",
                    f"duplicate component id: {cid}",
                ))
            by_id[cid] = c
        ctype = str(c.get("type") or c.get("op") or "").strip().upper()
        if not ctype:
            issues.append(AssemblyPlanIssue(
                f"{base}.params.assembly.graph.components[{j}].type",
                "E_COMPONENT_TYPE_MISSING",
                "component missing type",
            ))
    if not isinstance(connections, list):
        return
    for k, conn in enumerate(connections):
        if not isinstance(conn, dict):
            continue
        cp = f"{base}.params.assembly.graph.connections[{k}]"
        _validate_endpoint(issues, cp + ".from", conn.get("from"), by_id, "from")
        _validate_endpoint(issues, cp + ".to", conn.get("to"), by_id, "to")


def _validate_endpoint(
    issues: List[AssemblyPlanIssue],
    path: str,
    endpoint: Any,
    by_id: Dict[str, Dict[str, Any]],
    role: str,
) -> None:
    parsed = _parse_endpoint(endpoint)
    if parsed is None:
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
    if port not in ports:
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
        if "x" in endpoint or "y" in endpoint or "z" in endpoint:
            return None
        comp_id = endpoint.get("component") or endpoint.get("id")
        port = endpoint.get("port")
        return (str(comp_id).strip() if comp_id else None,
                str(port).strip() if port else None)
    return None


def _ports_for_component(comp: Dict[str, Any]) -> Set[str]:
    ports = set(BUILTIN_PORTS)
    ctype = str(comp.get("type") or comp.get("op") or "").upper()
    w = int(comp.get("w") or comp.get("width") or 10)
    d = int(comp.get("d") or comp.get("depth") or 10)
    h = int(comp.get("h") or comp.get("height") or 10)
    ports.add("top_center")
    if "SPLINE" in ctype:
        ports.update({"start", "end", "start_n", "start_s", "start_e", "start_w",
                      "end_n", "end_s", "end_e", "end_w"})
    if w > 0 or d > 0:
        hx, hz = w // 2, d // 2
        for name in (f"corner_{a}_{b}" for a in ("front", "back") for b in ("left", "right")):
            ports.add(name)
    if h > 0:
        ports.add("top")
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
        '- Prefer preset: { "preset": "spiral_watchtower"|"suspension_bridge_simple", "presetParams": {...} }.',
        '- Connection endpoints must be "ComponentId.port" using valid ports (center, top_center, bottom_center, north, ...).',
        "",
        "Previous plan JSON:",
    ])
    import json
    lines.append(json.dumps(plan, ensure_ascii=False))
    return "\n".join(lines)
