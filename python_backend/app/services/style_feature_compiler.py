"""Evidence-gated mappings to existing parameters; never claim voxel verification."""
from urllib.parse import urlparse
from copy import deepcopy

from ..models.building_profile import BuildingProfile, ProfileSource


def validate_style_evidence(profile: BuildingProfile, search_results: list[dict]) -> BuildingProfile:
    allowed = {str(r.get("url", "")).strip() for r in search_results
               if urlparse(str(r.get("url", ""))).scheme in ("https", "http")
               and urlparse(str(r.get("url", ""))).netloc}
    specs = []
    for spec in profile.style_specs:
        features = []
        for feature in spec.features:
            urls = list(dict.fromkeys(url.strip() for url in feature.source_urls if url.strip() in allowed))
            features.append(feature.model_copy(update={
                "source_urls": urls,
                "confidence": feature.confidence if urls else 0.0,
                "implementation_status": "unverified",
            }))
        specs.append(spec.model_copy(update={"features": features}))
    sources = [ProfileSource(title=str(r.get("title") or ""), url=str(r.get("url", "")).strip())
               for r in search_results if str(r.get("url", "")).strip() in allowed]
    return profile.model_copy(update={"style_specs": specs, "sources": sources})


def compile_style_feature_defaults(profile: BuildingProfile) -> list[dict]:
    """Only exact supported descriptions; scope must be bound before applying to a plan."""
    mappings = {"flat roof": "flat", "平屋顶": "flat",
                "gable roof": "gable", "双坡屋顶": "gable",
                "hip roof": "hip", "hipped roof": "hip", "四坡屋顶": "hip",
                "pyramid roof": "pyramid", "金字塔屋顶": "pyramid",
                "xuanshan roof": "xuanshan", "悬山屋顶": "xuanshan",
                "xieshan roof": "xieshan", "hip and gable roof": "xieshan", "歇山屋顶": "xieshan"}
    result = []
    for spec in profile.style_specs:
        for feature in spec.features:
            roof = mappings.get(feature.feature.strip().casefold())
            status = "unsupported" if roof is None else "insufficient_evidence"
            params = {}
            if roof and feature.source_urls and feature.confidence >= 0.6:
                status = "mapped_default"
                params = {"roof_type": roof}
            result.append({"style": spec.requested_name, "feature": feature.feature,
                           "scope": feature.scope, "style_scope": spec.scope,
                           "component_type": "ROOF", "params": params,
                           "status": status, "source_urls": feature.source_urls})
    return result


def apply_style_feature_defaults(plan: dict, profile: BuildingProfile) -> dict:
    """Bind exact contract IDs and fill absent parameters only. Never infer owners by order."""
    out = deepcopy(plan)
    components = [c for c in out.get("components", []) if isinstance(c, dict)]
    roofs = [c for c in components if c.get("component_type") == "ROOF"]
    masses = [c for c in components if c.get("component_type") in ("MASS_MAIN", "MASS_SECONDARY", "MASS_ANCILLARY")]
    hints = out.setdefault("proportion_hints", {})
    contract = hints.get("building_contract") or {}
    requirements = contract.get("requirements", [])
    report = []
    bound = []
    generic = {"", "building", "roof", "unspecified"}

    def matches(component, scope):
        params = component.get("params") or {}
        return scope in {params.get("component_id"), params.get("building_id"), params.get("requirement_scope")}

    for mapping in compile_style_feature_defaults(profile):
        entry = dict(mapping)
        entry["parameter_verification"] = "not_checked"
        report.append(entry)
        if mapping["status"] != "mapped_default":
            continue
        candidates = roofs
        for scope in (mapping["style_scope"], mapping["scope"]):
            if scope not in generic:
                candidates = [roof for roof in candidates if matches(roof, scope)
                              or any(matches(mass, scope) and (roof.get("params") or {}).get("host_id")
                                     == (mass.get("params") or {}).get("component_id") for mass in masses)]
        if not candidates:
            entry["status"] = "missing_target"
            continue
        if len(candidates) != 1:
            entry["status"] = "ambiguous_binding"
            continue
        roof = candidates[0]
        params = roof.get("params") or {}
        component_id = params.get("component_id")
        if not component_id or sum((c.get("params") or {}).get("component_id") == component_id for c in components) != 1:
            entry["status"] = "invalid_component_identity"
            continue
        hosts = [mass for mass in masses if (mass.get("params") or {}).get("component_id") == params.get("host_id")]
        if len(hosts) != 1:
            entry["status"] = "unresolved_host"
            continue
        host = hosts[0]
        entry["component_id"] = component_id
        entry["host_id"] = params["host_id"]
        bound.append((entry, roof, host))

    for entry, roof, host in bound:
        params = roof.setdefault("params", {})
        host_params = host.get("params") or {}
        expected = entry["params"]["roof_type"]
        own_scopes = {"all_main_masses", "plan", host_params.get("requirement_scope")}
        user_requirements = [r for r in requirements if r.get("property") == "roof_type"
                             and r.get("source") == "user_explicit"
                             and r.get("scope") in own_scopes]
        if user_requirements:
            entry["status"] = "user_override"
            entry["parameter_verification"] = "deferred_to_building_contract"
            continue
        if any(r.get("property") == "roof_type" and r.get("source") == "user_explicit"
               and r.get("scope") in ("unresolved", "unsupported_scope") for r in requirements):
            entry["status"] = "unresolved_user_scope"
            continue
        competing = {other["params"]["roof_type"] for other, target, _ in bound if target is roof}
        if len(competing) > 1:
            entry["status"] = "conflicting_defaults"
            continue
        actual = params.get("roof_type", host_params.get("roof_type"))
        if actual is None:
            params["roof_type"] = expected
            entry["status"] = "applied_default"
            entry["parameter_verification"] = "matched"
        elif str(actual).casefold() == expected:
            entry["status"] = "matched_existing"
            entry["parameter_verification"] = "matched"
        else:
            entry["status"] = "existing_parameter_conflict"
            entry["parameter_verification"] = "mismatch"
        entry["actual_value"] = params.get("roof_type", host_params.get("roof_type"))
    hints["style_feature_report"] = {"schema": "formacraft.style_feature_report.v1",
                                    "verification_level": "plan_parameters_only", "features": report}
    return out
