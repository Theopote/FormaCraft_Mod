"""Per-host catalogue defaults without resizing a compound building plan."""
from copy import deepcopy

from .style_identity import canonical_style_id
from .style_profile_registry import get_style_profile


def enrich_hosted_styles(plan: dict, profile=None) -> dict:
    out = deepcopy(plan)
    components = [c for c in out.get("components", []) if isinstance(c, dict)]
    masses = [c for c in components if c.get("component_type") == "MASS_MAIN"]
    hints = out.setdefault("proportion_hints", {})
    requirements = (hints.get("building_contract") or {}).get("requirements", [])
    ids = [(c.get("params") or {}).get("component_id") for c in components]
    reports = []
    for mass in masses:
        params = mass.setdefault("params", {})
        identity = params.get("component_id")
        report = {"host_id": identity, "changes": [], "skipped": []}
        reports.append(report)
        if not identity or ids.count(identity) != 1:
            report["status"] = "invalid_host_identity"
            continue
        scopes = {identity, params.get("building_id"), params.get("requirement_scope")} - {None, ""}
        research_styles = {canonical_style_id(spec.identity_id or spec.requested_name)
                           for spec in (profile.style_specs if profile else []) if spec.scope in scopes}
        local = mass.get("style_profile") or params.get("style_profile")
        if local:
            style = canonical_style_id(local)
        elif len(research_styles) == 1:
            style = next(iter(research_styles))
        elif len(research_styles) > 1:
            report["status"] = "conflicting_scoped_styles"
            continue
        else:
            style = canonical_style_id(out.get("style_profile"))
        report["style_id"] = style
        card = get_style_profile(style) if style else None
        if card is None:
            report["status"] = "unsupported_style"
            continue
        defaults = card.defaults or {}
        facade = (defaults.get("components") or {}).get("facade_profile")
        roof_type = ((defaults.get("geometry") or {}).get("roof") or {}).get("type")
        own = [r for r in requirements if r.get("source") == "user_explicit"
               and r.get("scope") in scopes | {"plan", "all_main_masses"}]
        blocked_roof = any(r.get("property") == "roof_type" and r.get("source") == "user_explicit"
                           and r.get("scope") in scopes | {"plan", "all_main_masses", "unresolved", "unsupported_scope"}
                           for r in requirements)
        blocked_decor = any(r.get("property") == "no_complex_decor" and r.get("value") is True for r in own)
        if facade and not blocked_decor and "facade_profile" not in params:
            params["facade_profile"] = facade
            report["changes"].append({"component_id": identity, "parameter": "facade_profile", "value": facade})
        for component in components:
            cp = component.get("params") or {}
            if cp.get("host_id") != identity or component.get("component_type") != "ROOF":
                continue
            cid = cp.get("component_id")
            if (not cid or ids.count(cid) != 1 or cp.get("host_source") == "legacy_geometry_inference"
                    or cp.get("host_ids") or any("bridge" in str(f).lower() or "连廊" in str(f)
                                               for f in component.get("features", []))):
                report["skipped"].append(cid)
                continue
            if roof_type in {"flat", "gable", "hip", "mansard"} and not blocked_roof and "roof_type" not in cp and "roof_type" not in params:
                component.setdefault("params", {})["roof_type"] = roof_type
                report["changes"].append({"component_id": cid, "parameter": "roof_type", "value": roof_type})
        report["status"] = "processed"
    hints["hosted_style_enrichment"] = {"schema": "formacraft.hosted_style_enrichment.v1",
                                        "geometry_changed": False, "hosts": reports}
    return out
