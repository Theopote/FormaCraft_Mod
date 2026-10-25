"""Definite cross-component contradictions sent through the existing repair loop."""
from typing import Any
import copy
import re


def resolve_delegated_entrances(plan: dict[str, Any], user_text: str) -> dict[str, Any]:
    """Separate model inline-door suppression from an explicit user no-entry requirement."""
    no_entry = (r"(?:不要|不设|禁止|不需要)\s*(?:任何|生成|设置)?\s*(?:门(?!框|槛|廊)|入口)"
                r"|无门(?!框|槛|廊)|无入口|取消入口"
                r"|\b(?:no|without)\s+(?:any\s+)?(?:doors?|entrances?)\b"
                r"|\b(?:do not|don't)\s+(?:add|create|generate|include)\s+(?:any\s+)?(?:doors?|entrances?)\b")
    if any(re.search(no_entry, text, re.I) for text in (user_text or "", re.sub(r"\s+", "", user_text or ""))):
        return plan
    hints = plan.get("proportion_hints")
    hints = hints if isinstance(hints, dict) else {}
    contract = hints.get("building_contract")
    contract = contract if isinstance(contract, dict) else {}
    requirements = contract.get("requirements")
    requirements = requirements if isinstance(requirements, list) else []
    if any(r.get("property") == "entrance_type" and r.get("value") == "none"
           for r in requirements if isinstance(r, dict)):
        return plan
    raw = plan.get("components")
    if not isinstance(raw, list):
        return plan
    hosts = {c.get("params", {}).get("host_id") for c in raw
             if isinstance(c, dict) and c.get("component_type") == "ENTRANCE" and isinstance(c.get("params"), dict)}
    out = copy.deepcopy(plan)
    for c in out["components"]:
        if not isinstance(c, dict) or c.get("component_type") != "MASS_MAIN" or not isinstance(c.get("params"), dict):
            continue
        p = c.get("params") or {}
        if p.get("component_id") is not None and p.get("component_id") in hosts and p.get("entrance_type") == "none":
            p["entrance_type"] = "door"
            p["suppress_doors"] = True
            p["compiler_delegated_entrance"] = True
    return out


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
