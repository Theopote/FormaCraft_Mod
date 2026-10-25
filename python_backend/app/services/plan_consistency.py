"""Definite cross-component contradictions sent through the existing repair loop."""
from typing import Any


def component_consistency_errors(plan: dict[str, Any]) -> list[str]:
    if plan.get("plan_status") == "capability_gap":
        return []
    raw = plan.get("components")
    components = [c for c in raw if isinstance(c, dict)] if isinstance(raw, list) else []
    bodies = {
        c.get("params", {}).get("component_id"): c
        for c in components
        if c.get("component_type") == "MASS_MAIN" and isinstance(c.get("params"), dict)
    }
    errors = []
    for c in components:
        p = c.get("params") if isinstance(c.get("params"), dict) else {}
        if p.get("host_id") is None:
            continue
        body = bodies.get(p.get("host_id"))
        if c.get("component_type") != "ENTRANCE" or not body:
            continue
        bp = body.get("params") or {}
        if str(bp.get("entrance_type", "")).lower() in {"none", "off", "disabled"}:
            errors.append(
                "component_consistency: ENTRANCE " + str(p.get("component_id", ""))
                + " targets host " + str(p.get("host_id"))
                + " whose entrance_type=none. Resolve the contradiction while preserving the user's "
                "requested entrances and circulation; do not disable conflict validation."
            )
    return errors
