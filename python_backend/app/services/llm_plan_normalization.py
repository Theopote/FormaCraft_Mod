"""Deterministic LlmPlan output normalization, separate from model invocation."""

import logging
from typing import Any, Dict, Optional
from ..models.request import BuildRequest
from .legacy_spec_genome import _deep_merge
from .planning_request_hints import _parse_levels_from_text
from .llm_plan_component_defaults import _fill_component_params
from .typology_plan_repair import _sanitize_landmark_modules
from .llm_plan_layout_normalization import _normalize_global_symmetry_value, _sanitize_genome_dict, _sanitize_layout_slots
from .llm_plan_dimensions import _MASS_COMPONENT_TYPES, _normalize_vec3, _extract_mass_dims, _param_string, _normalize_dimensions
from .llm_plan_wire_normalization import _normalize_component_type, _normalize_features
from .planning_selection_bounds import _selection_dimensions, _clamp_dimensions_to_selection, _selection_bounds_relative, _clamp_relative_position
from ..models.building_genome import BuildingGenome

logger = logging.getLogger(__name__)

_LOCKED_DESIGN_SOURCES = frozenset({"USER_EXPLICIT", "AI_PLANNED"})
_DESIGN_SOURCE_RANK = {
    "TECHNICAL_DEFAULT": 0,
    "DERIVED_GEOMETRY": 1,
    "AI_PLANNED": 2,
    "USER_EXPLICIT": 3,
}


def _ensure_component_design_sources(params: Dict[str, Any]) -> Dict[str, Any]:
    sources = params.get("design_sources")
    if not isinstance(sources, dict):
        sources = {}
        params["design_sources"] = sources
    return sources


def _tag_param_source(params: Dict[str, Any], key: str, source: str) -> None:
    """Stamp params.design_sources[key] without downgrading USER_EXPLICIT / AI_PLANNED."""
    if not key or not source:
        return
    sources = _ensure_component_design_sources(params)
    existing = str(sources.get(key) or "").strip().upper()
    incoming = source.strip().upper()
    if existing in _LOCKED_DESIGN_SOURCES and _DESIGN_SOURCE_RANK.get(incoming, -1) <= _DESIGN_SOURCE_RANK.get(existing, -1):
        return
    if existing and _DESIGN_SOURCE_RANK.get(incoming, -1) < _DESIGN_SOURCE_RANK.get(existing, -1):
        return
    sources[key] = incoming


def _tag_present_param_as_ai_planned(params: Dict[str, Any], *keys: str) -> None:
    """Tag authored values as AI_PLANNED when design_sources lacks an entry."""
    for key in keys:
        value = params.get(key)
        if value is None:
            continue
        if isinstance(value, str) and not value.strip():
            continue
        sources = params.get("design_sources")
        if isinstance(sources, dict) and str(sources.get(key) or "").strip():
            continue
        _tag_param_source(params, key, "AI_PLANNED")


def _tag_plan_level_source(plan: Dict[str, Any], key: str, source: str) -> None:
    sources = plan.get("design_sources")
    if not isinstance(sources, dict):
        sources = {}
        plan["design_sources"] = sources
    existing = str(sources.get(key) or "").strip().upper()
    incoming = source.strip().upper()
    if existing in _LOCKED_DESIGN_SOURCES and _DESIGN_SOURCE_RANK.get(incoming, -1) < _DESIGN_SOURCE_RANK.get(existing, -1):
        return
    if existing and _DESIGN_SOURCE_RANK.get(incoming, -1) < _DESIGN_SOURCE_RANK.get(existing, -1):
        return
    sources[key] = incoming


def _resolve_user_explicit_roof(user_text: str) -> Optional[str]:
    """Return explicit roof_type from user text, or None when not stated."""
    text = (user_text or "").strip()
    if not text:
        return None
    from .request_intent import extract_request_intent
    from .building_contract import extract_requirements

    intent = extract_request_intent(text)
    if intent.get("roof_explicit") and intent.get("roof_type"):
        return str(intent["roof_type"]).strip().lower() or None
    for requirement in extract_requirements(text):
        if requirement.get("property") != "roof_type":
            continue
        if str(requirement.get("source") or "").lower() not in ("user_explicit", "user", "player"):
            continue
        value = requirement.get("value")
        if value is None:
            continue
        return str(value).strip().lower() or None
    return None


def _merge_design_requirements(plan: Dict[str, Any], updates: Dict[str, str]) -> None:
    if not updates:
        return
    meta = plan.get("plan_meta")
    if not isinstance(meta, dict):
        meta = {}
        plan["plan_meta"] = meta
    requirements = meta.get("design_requirements")
    if not isinstance(requirements, dict):
        requirements = {}
        meta["design_requirements"] = requirements
    for key, value in updates.items():
        existing = str(requirements.get(key) or "").strip().upper()
        # Never downgrade an explicit OMITTED / REQUIRED already authored by the model/user.
        if existing in ("OMITTED", "REQUIRED") and existing != value.upper():
            if existing == "OMITTED":
                continue
        requirements[key] = value


def _ensure_compiler_satellite_policy(plan: Dict[str, Any]) -> Dict[str, Any]:
    """Make ``allow_compiler_satellites`` explicit — opt-in only, never auto-authorize.

    Classification: FORMAT_REPAIR / policy stamp — does not invent geometry.
    Missing ROOF/FACADE/ENTRANCE is not proof of incompleteness (open frames,
    intentional bare shells). Java reports ``E_DESIGN_INCOMPLETE`` when strict
    and UNSPECIFIED requirements demand satellites; AI should replan or mark
    ``design_requirements`` OMITTED/OPTIONAL. Already-set keys (scaffolds,
    transitional fixtures) are left unchanged.
    """
    if not isinstance(plan, dict):
        return plan
    if str(plan.get("plan_status") or "").lower() == "capability_gap":
        return plan
    meta = plan.get("plan_meta")
    if not isinstance(meta, dict):
        meta = {}
        plan["plan_meta"] = meta
    if "allow_compiler_satellites" in meta:
        return plan
    meta["allow_compiler_satellites"] = False
    meta["compiler_satellites_policy"] = "ai_owned"
    return plan


_COURTYARD_LITERALS = (
    "courtyard", "siheyuan", "中庭", "庭院", "四合院", "院落", "内院",
)
_MASS_TYPES_FOR_EXPLICIT = (
    "MASS_MAIN", "MASS_SECONDARY", "MASS_WING", "SIDE_WING", "MAIN_MASS", "TOWER", "GATE",
)
_COURTYARD_NAME_HINTS = (
    "courtyard", "yard", "庭院", "中庭", "四合院", "院落", "内院", "siheyuan",
)


def _user_explicit_courtyard(text: str) -> bool:
    from .planning_request_hints import _keyword_hit_unnegated
    return _keyword_hit_unnegated((text or "").lower(), _COURTYARD_LITERALS)


def _is_mass_like(ctype: str) -> bool:
    t = (ctype or "").strip().upper()
    return t.startswith("MASS") or t in _MASS_TYPES_FOR_EXPLICIT


def _mass_like_components(components: list) -> list:
    return [
        c for c in components
        if isinstance(c, dict) and _is_mass_like(str(c.get("component_type") or ""))
    ]


def _comp_id(comp: Dict[str, Any]) -> str:
    params = comp.get("params") if isinstance(comp.get("params"), dict) else {}
    return str(params.get("component_id") or "").strip()


def _looks_courtyard_named(comp: Dict[str, Any]) -> bool:
    params = comp.get("params") if isinstance(comp.get("params"), dict) else {}
    blob = " ".join(
        str(x or "").lower()
        for x in (
            comp.get("component_type"),
            comp.get("slot_id"),
            params.get("component_id"),
            params.get("role"),
            params.get("label"),
            params.get("plan_type"),
        )
    )
    return any(hint in blob for hint in _COURTYARD_NAME_HINTS)


def _resolve_scoped_mass_targets(
    masses: list,
    scope: str | None,
    target_ids: list | None,
    *,
    scope_source: str | None = None,
) -> list:
    """Resolve which MASS components a user constraint may touch.

    ``all_main_masses`` / ``plan`` expand to every mass only when ``scope_source`` is an
    explicit quantifier (or target_components uniquely identify hosts). Inferred defaults,
    unresolved names, and bare ``scope=plan`` without authorization return empty so
    AI authorship on sibling masses is preserved.
    """
    if not masses:
        return []
    ids = {str(x).strip() for x in (target_ids or []) if x}
    if ids:
        hit = [m for m in masses if _comp_id(m) in ids]
        return hit
    scope_key = str(scope or "").strip()
    source = str(scope_source or "").strip().upper()
    if scope_key in ("unresolved", "element_scope"):
        return []
    if scope_key and scope_key not in ("plan", "all_main_masses"):
        scoped = [
            m for m in masses
            if str((m.get("params") or {}).get("requirement_scope") or "").strip() == scope_key
            or _comp_id(m) == scope_key
        ]
        return scoped
    if len(masses) <= 1:
        return list(masses)
    if scope_key in ("all_main_masses", "plan"):
        # Whole-compound overwrite requires an explicit quantifier / EXPLICIT stamp.
        # Empty scope_source must NOT escalate intent-keyword forbids to every mass.
        if source in ("EXPLICIT_QUANTIFIER", "EXPLICIT"):
            return list(masses)
        return []
    return []


def _strip_forged_user_explicit(plan: Dict[str, Any]) -> None:
    """Demote LLM-declared USER_EXPLICIT before server re-stamps from player text.

    Classification: FORMAT_REPAIR / trust boundary — does not invent geometry.
    """
    if not isinstance(plan, dict):
        return
    plan_sources = plan.get("design_sources")
    if isinstance(plan_sources, dict):
        for key, raw in list(plan_sources.items()):
            if str(raw or "").strip().upper() in ("USER_EXPLICIT", "USER", "PLAYER"):
                plan_sources[key] = "AI_PLANNED"
    components = plan.get("components")
    if not isinstance(components, list):
        return
    for comp in components:
        if not isinstance(comp, dict):
            continue
        params = comp.get("params")
        if not isinstance(params, dict):
            continue
        # Never trust client/LLM copies of Java verification markers.
        params.pop("_server_verified_sources", None)
        params.pop("_fc_constraint_seal", None)
        sources = params.get("design_sources")
        if not isinstance(sources, dict):
            continue
        for key, raw in list(sources.items()):
            if str(raw or "").strip().upper() in ("USER_EXPLICIT", "USER", "PLAYER"):
                sources[key] = "AI_PLANNED"


def _user_reqs(requirements: list, prop: str) -> list:
    """Keep unresolved *scopes* — binding/diagnostics happen in ``_targets_for_req``.

    Drop only requirements whose *status* is unsupported/unresolved (no usable value).
    """
    return [
        r for r in requirements
        if str(r.get("property") or "") == prop
        and str(r.get("source") or "").lower() in ("user_explicit", "user", "player")
        and r.get("value") is not None
        and str(r.get("status") or "").lower() not in ("unresolved", "unsupported_scope")
    ]


def _targets_for_req(masses: list, req: dict) -> list:
    targets = _resolve_scoped_mass_targets(
        masses,
        str(req.get("scope") or ""),
        list(req.get("target_components") or []),
        scope_source=str(req.get("scope_source") or ""),
    )
    if not targets and len(masses) <= 1:
        # Single-mass compounds: unresolved INFERRED roof may still bind locally.
        scope = str(req.get("scope") or "").strip()
        if scope in ("", "unresolved", "plan", "all_main_masses"):
            return list(masses)
    return targets


_PAVILION_HOST_HINTS = (
    "pavilion", "gazebo", "ting", "kiosk", "凉亭", "亭子", "水上亭", "水榭", "八角亭", "六角亭", "方亭", "亭",
)
_HALL_HOST_HINTS = (
    "hall", "house", "main", "殿", "堂", "住宅", "主殿", "正殿", "main_hall",
)


def _mass_name_blob(comp: dict) -> str:
    params = comp.get("params") if isinstance(comp.get("params"), dict) else {}
    parts = [
        comp.get("component_type"),
        comp.get("slot_id"),
        params.get("component_id"),
        params.get("building_role"),
        params.get("buildingRole"),
        params.get("label"),
    ]
    if isinstance(comp.get("features"), list):
        parts.extend(comp.get("features") or [])
    return " ".join(str(x or "").lower() for x in parts)


def _infer_unique_named_roof_host(masses: list, req: dict, user_text: str = "") -> list:
    """When scope is unresolved, bind to a unique mass whose id/features match nearby cues."""
    if not masses or len(masses) <= 1:
        return []
    source_bits = req.get("source_text") or []
    cue = " ".join(str(x or "") for x in source_bits).lower()
    cue += " " + str(req.get("scope") or "").lower()
    cue += " " + (user_text or "").lower()
    want_pavilion = any(h in cue for h in _PAVILION_HOST_HINTS)
    want_hall = any(h in cue for h in _HALL_HOST_HINTS)
    if not want_pavilion and not want_hall:
        return []
    hits = []
    for mass in masses:
        blob = _mass_name_blob(mass)
        if want_pavilion and any(h in blob for h in _PAVILION_HOST_HINTS):
            hits.append(mass)
        elif want_hall and any(h in blob for h in _HALL_HOST_HINTS):
            hits.append(mass)
    # Prefer pavilion hits when both cues appear (house + pavilion compounds).
    if want_pavilion:
        pavilion_hits = [
            m for m in hits if any(h in _mass_name_blob(m) for h in _PAVILION_HOST_HINTS)
        ]
        if len(pavilion_hits) == 1:
            return pavilion_hits
    return hits if len(hits) == 1 else []


def _covers_all_masses(masses: list, targets: list) -> bool:
    if not masses or not targets:
        return False
    if len(masses) == 1 and len(targets) == 1:
        return True
    ids = {_comp_id(m) for m in masses if _comp_id(m)}
    hit = {_comp_id(t) for t in targets if _comp_id(t)}
    return bool(ids) and ids <= hit


def _record_scope_ambiguity(
    plan: Dict[str, Any],
    *,
    code: str,
    message: str,
    detail: dict | None = None,
) -> None:
    """Surface unresolved local design binding without guessing mass ownership."""
    meta = plan.get("plan_meta")
    if not isinstance(meta, dict):
        meta = {}
        plan["plan_meta"] = meta
    diags = meta.get("scope_diagnostics")
    if not isinstance(diags, list):
        diags = []
        meta["scope_diagnostics"] = diags
    entry: Dict[str, Any] = {"code": code, "message": message}
    if detail:
        entry["detail"] = detail
    if entry not in diags:
        diags.append(entry)


def _user_authorized_roof_overwrite(roof_reqs: list, roof_type: str, masses: list) -> bool:
    """True when a USER_EXPLICIT requirement authorizes overwriting AI roofs for this type.

    Whole-compound overwrite requires ``scope_source=EXPLICIT_QUANTIFIER`` (or EXPLICIT).
    Named / ordinal scopes and ``target_components`` are also authorized.
    INFERRED / unresolved scopes are not.
    """
    if len(masses) <= 1:
        return True
    for req in roof_reqs:
        if str(req.get("value") or "").strip().lower() != roof_type:
            continue
        if list(req.get("target_components") or []):
            return True
        scope = str(req.get("scope") or "").strip()
        source = str(req.get("scope_source") or "").strip().upper()
        if scope in ("unresolved", "element_scope"):
            continue
        if scope in ("plan", "all_main_masses"):
            if source in ("EXPLICIT_QUANTIFIER", "EXPLICIT"):
                return True
            # Legacy crafted req without scope_source still authorizes when scope is explicit.
            if source == "" and scope in ("plan", "all_main_masses"):
                return True
            continue
        if scope:
            return True
    return False


def _hosted_roofs_consistent(
    components: list,
    target_ids: set[str],
    roof_type: str,
    extras: dict | None = None,
    *,
    reject_unbound: bool = False,
) -> bool:
    """True when every ROOF/ROOF_STRUCTURE bound to targets matches roof_type (+ extras).

    Unbound roofs only fail when ``reject_unbound`` (whole-compound success claims).
    """
    extras = extras or {}
    for comp in components:
        if not isinstance(comp, dict):
            continue
        ctype = str(comp.get("component_type") or "").upper()
        if ctype not in ("ROOF", "ROOF_STRUCTURE"):
            continue
        params = comp.get("params") if isinstance(comp.get("params"), dict) else {}
        host = str(params.get("host_id") or "").strip()
        if not host:
            if reject_unbound and target_ids:
                return False
            continue
        if host not in target_ids:
            continue
        current = str(params.get("roof_type") or "").strip().lower()
        if current != roof_type:
            return False
        for ek, ev in extras.items():
            if ev is None:
                continue
            if params.get(ek) != ev:
                return False
    return True


def _stamp_roof_on_targets(
    plan: Dict[str, Any],
    components: list,
    masses: list,
    targets: list,
    roof_type: str,
    *,
    allow_overwrite: bool,
    roof_extras: dict | None = None,
) -> bool:
    """Apply roof_type only to targeted masses and ROOFs with an explicit host_id bind.

    Returns True when every target mass AND every hosted ROOF/ROOF_STRUCTURE matches
    ``roof_type`` (plan-level lock safe only then).
    """
    target_ids = {_comp_id(t) for t in targets if _comp_id(t)}
    extras = {k: v for k, v in (roof_extras or {}).items() if v is not None}
    for comp in components:
        if not isinstance(comp, dict):
            continue
        ctype = str(comp.get("component_type") or "").upper()
        params = comp.get("params")
        if not isinstance(params, dict):
            params = {}
            comp["params"] = params
        if _is_mass_like(ctype):
            cid = _comp_id(comp)
            matched = (cid and cid in target_ids) or any(comp is t for t in targets)
            if not matched:
                continue
            current = str(params.get("roof_type") or "").strip().lower()
            if current and current != roof_type and not allow_overwrite:
                continue
            params["roof_type"] = roof_type
            _tag_param_source(params, "roof_type", "USER_EXPLICIT")
            for ek, ev in extras.items():
                params[ek] = ev
                _tag_param_source(params, ek, "USER_EXPLICIT")
        elif ctype in ("ROOF", "ROOF_STRUCTURE"):
            host = str(params.get("host_id") or "").strip()
            # Ambiguous unbound roofs must not be rewritten from a local mass constraint.
            if not host or host not in target_ids:
                continue
            current = str(params.get("roof_type") or "").strip().lower()
            if current and current != roof_type and not allow_overwrite:
                continue
            params["roof_type"] = roof_type
            _tag_param_source(params, "roof_type", "USER_EXPLICIT")
            for ek, ev in extras.items():
                params[ek] = ev
                _tag_param_source(params, ek, "USER_EXPLICIT")
    masses_ok = bool(targets) and all(
        str((t.get("params") or {}).get("roof_type") or "").strip().lower() == roof_type
        for t in targets
        if isinstance(t, dict)
    )
    whole_compound = _covers_all_masses(masses, targets)
    roofs_ok = _hosted_roofs_consistent(
        components, target_ids, roof_type, extras, reject_unbound=whole_compound
    )
    applied_all = masses_ok and roofs_ok
    if masses_ok and not roofs_ok:
        _record_scope_ambiguity(
            plan,
            code="E_ROOF_COMPONENT_INCONSISTENT",
            message=f"roof_type={roof_type} on masses but hosted ROOF/ROOF_STRUCTURE still disagree",
            detail={"target_ids": sorted(target_ids)},
        )
    # Plan-level USER_EXPLICIT only after the constraint actually landed on every target.
    if applied_all and _covers_all_masses(masses, targets):
        _tag_plan_level_source(plan, "roof_type", "USER_EXPLICIT")
    elif _covers_all_masses(masses, targets) and not applied_all:
        _record_scope_ambiguity(
            plan,
            code="E_ROOF_APPLY_INCOMPLETE",
            message=f"roof_type={roof_type} authorized for all masses but could not overwrite existing AI roofs",
        )
    return applied_all

def _apply_user_explicit_design_sources(plan: Dict[str, Any], user_text: str) -> Dict[str, Any]:
    """Stamp USER_EXPLICIT provenance for player-stated design constraints and omissions.

    Constraints are component-scoped. Unscoped keywords must not overwrite AI plans
    across multiple masses (e.g. 主殿歇山 + 附属平屋顶, or 住宅旁庭院).
    """
    if not isinstance(plan, dict):
        return plan
    from .request_intent import extract_request_intent
    from .building_contract import extract_requirements

    # LLM may forge USER_EXPLICIT in JSON — demote before server re-stamps from player text.
    _strip_forged_user_explicit(plan)

    text = (user_text or "").strip()
    intent = extract_request_intent(text) if text else {}
    intent_forbids = {str(x).strip().lower() for x in (intent.get("forbids") or []) if x}
    requirements = extract_requirements(text) if text else []
    explicit_courtyard = _user_explicit_courtyard(text)
    roof_reqs = _user_reqs(requirements, "roof_type")
    window_omit_reqs = [
        r for r in _user_reqs(requirements, "window_style")
        if str(r.get("value") or "").strip().lower() == "none"
    ]
    entrance_omit_reqs = [
        r for r in _user_reqs(requirements, "entrance_type")
        if str(r.get("value") or "").strip().lower() == "none"
    ]
    roof_omit_reqs = [
        r for r in roof_reqs
        if str(r.get("value") or "").strip().lower() == "none"
    ]
    fallback_roof = _resolve_user_explicit_roof(text)

    components = plan.get("components")
    if not isinstance(components, list):
        return plan

    masses = _mass_like_components(components)

    def _single_mass_plan_omit() -> dict:
        return {
            "scope": "plan",
            "value": "none",
            "source": "user_explicit",
            "scope_source": "EXPLICIT_QUANTIFIER",
        }

    def _note_omit_ambiguous(kind: str) -> None:
        _record_scope_ambiguity(
            plan,
            code="E_OMIT_SCOPE_AMBIGUOUS",
            message=(
                f"local {kind} omission could not bind to a unique mass; "
                "refusing to delete that element on every building"
            ),
            detail={"mass_count": len(masses)},
        )

    # Intent-keyword / fallback "none" may apply only when a single mass is unambiguous.
    # Multi-mass compounds must not escalate an unresolved local forbid into scope=plan.
    if fallback_roof == "none" and not roof_omit_reqs:
        if len(masses) <= 1:
            roof_omit_reqs = [_single_mass_plan_omit()]
        else:
            _note_omit_ambiguous("roof")
    if "windows" in intent_forbids and not window_omit_reqs:
        if len(masses) <= 1:
            window_omit_reqs = [_single_mass_plan_omit()]
        else:
            _note_omit_ambiguous("windows")
    if "entrance" in intent_forbids and not entrance_omit_reqs:
        if len(masses) <= 1:
            entrance_omit_reqs = [_single_mass_plan_omit()]
        else:
            _note_omit_ambiguous("entrance")
    if "roof" in intent_forbids and not roof_omit_reqs:
        if len(masses) <= 1:
            roof_omit_reqs = [_single_mass_plan_omit()]
        else:
            _note_omit_ambiguous("roof")

    if (
        not roof_reqs and not fallback_roof and not roof_omit_reqs
        and not window_omit_reqs and not entrance_omit_reqs and not explicit_courtyard
    ):
        _promote_hard_scope_diagnostics(plan)
        return plan

    roof_omit_targets: list = []
    for req in roof_omit_reqs:
        hits = _targets_for_req(masses, req)
        if not hits and len(masses) > 1:
            _note_omit_ambiguous("roof")
        for t in hits:
            if t not in roof_omit_targets:
                roof_omit_targets.append(t)
    window_omit_targets: list = []
    for req in window_omit_reqs:
        hits = _targets_for_req(masses, req)
        if not hits and len(masses) > 1:
            _note_omit_ambiguous("windows")
        for t in hits:
            if t not in window_omit_targets:
                window_omit_targets.append(t)
    entrance_omit_targets: list = []
    for req in entrance_omit_reqs:
        hits = _targets_for_req(masses, req)
        if not hits and len(masses) > 1:
            _note_omit_ambiguous("entrance")
        for t in hits:
            if t not in entrance_omit_targets:
                entrance_omit_targets.append(t)

    # Plan-level OMITTED only when omission covers every mass.
    design_req_updates: Dict[str, str] = {}
    if roof_omit_targets and _covers_all_masses(masses, roof_omit_targets):
        design_req_updates["roof"] = "OMITTED"
    if window_omit_targets and _covers_all_masses(masses, window_omit_targets):
        design_req_updates["facade"] = "OMITTED"
    if entrance_omit_targets and _covers_all_masses(masses, entrance_omit_targets):
        design_req_updates["entrance"] = "OMITTED"
    _merge_design_requirements(plan, design_req_updates)

    # Resolve roof type targets per scoped requirement; never paint every mass from one keyword.
    roof_targets: dict[str, list] = {}
    for req in roof_reqs:
        value = str(req.get("value") or "").strip().lower()
        if not value or value == "none":
            continue
        targets = _targets_for_req(masses, req)
        if not targets:
            # Soft name bind: unique pavilion/hall cue from player text may identify one host.
            targets = _infer_unique_named_roof_host(masses, req, user_text=text)
        if targets:
            roof_targets.setdefault(value, [])
            for t in targets:
                if t not in roof_targets[value]:
                    roof_targets[value].append(t)

    if not roof_targets and fallback_roof and fallback_roof != "none":
        if len(masses) <= 1:
            roof_targets[fallback_roof] = list(masses)
        else:
            inferred = _infer_unique_named_roof_host(
                masses, {"source_text": [fallback_roof], "scope": "unresolved"}, user_text=text
            )
            if inferred:
                roof_targets[fallback_roof] = inferred
            else:
                # Multi-mass + unscoped single roof literal: do not paint every mass.
                _record_scope_ambiguity(
                    plan,
                    code="E_ROOF_SCOPE_AMBIGUOUS",
                    message="unscoped roof constraint with multiple masses; left AI plan intact",
                )

    # Scoped roof requirements that failed to resolve any host.
    # Skip when AI already satisfies the value on exactly one mass (leave-intact is correct).
    for req in roof_reqs:
        value = str(req.get("value") or "").strip().lower()
        if not value or value == "none":
            continue
        if _targets_for_req(masses, req) or _infer_unique_named_roof_host(masses, req, user_text=text):
            continue
        already = [
            m for m in masses
            if str((m.get("params") or {}).get("roof_type") or "").strip().lower() == value
        ]
        if len(already) == 1:
            continue
        if len(masses) > 1:
            _record_scope_ambiguity(
                plan,
                code="E_ROOF_HOST_UNRESOLVED",
                message=f"roof_type={value} could not bind to a unique mass",
                detail={"scope": req.get("scope"), "target_components": req.get("target_components")},
            )

    courtyard_targets: list = []
    if explicit_courtyard:
        courtyard_comps = [
            c for c in components
            if isinstance(c, dict) and str(c.get("component_type") or "").upper() == "COURTYARD"
        ]
        if courtyard_comps:
            for c in courtyard_comps:
                params = c.get("params") if isinstance(c.get("params"), dict) else {}
                if not isinstance(c.get("params"), dict):
                    c["params"] = params
                _tag_param_source(params, "plan_type", "USER_EXPLICIT")
        elif len(masses) <= 1:
            courtyard_targets = list(masses)
        else:
            named = [m for m in masses if _looks_courtyard_named(m)]
            if len(named) == 1:
                courtyard_targets = named

    roof_omit_ids = {_comp_id(m) for m in roof_omit_targets if _comp_id(m)}
    window_omit_ids = {_comp_id(m) for m in window_omit_targets if _comp_id(m)}
    entrance_omit_ids = {_comp_id(m) for m in entrance_omit_targets if _comp_id(m)}
    # Masses without component_id: match by identity via target list membership.
    def _mass_in(targets: list, comp: dict) -> bool:
        return any(comp is t or (_comp_id(comp) and _comp_id(comp) == _comp_id(t)) for t in targets)

    kept: list = []
    for comp in components:
        if not isinstance(comp, dict):
            continue
        ctype = str(comp.get("component_type") or "").upper()
        params = comp.get("params")
        if not isinstance(params, dict):
            params = {}
            comp["params"] = params
        host = str(params.get("host_id") or "").strip()
        cid = _comp_id(comp)

        drop = False
        if ctype in ("ROOF", "ROOF_STRUCTURE") and roof_omit_targets:
            if host and host in roof_omit_ids:
                drop = True
            elif not host and len(masses) <= 1 and roof_omit_targets:
                drop = True
        if ctype in ("FACADE_WINDOWS", "FACADE") and window_omit_targets:
            if host and host in window_omit_ids:
                drop = True
            elif not host and len(masses) <= 1 and window_omit_targets:
                drop = True
        if ctype == "ENTRANCE" and entrance_omit_targets:
            if host and host in entrance_omit_ids:
                drop = True
            elif not host and len(masses) <= 1 and entrance_omit_targets:
                drop = True
        if drop:
            continue

        if _is_mass_like(ctype):
            if _mass_in(window_omit_targets, comp):
                params["window_style"] = "none"
                params["window_ratio"] = 0
                params["facade_requirement"] = "OMITTED"
                params["window_requirement"] = "OMITTED"
                _tag_param_source(params, "window_style", "USER_EXPLICIT")
                _tag_param_source(params, "window_ratio", "USER_EXPLICIT")
                if _covers_all_masses(masses, window_omit_targets):
                    _tag_plan_level_source(plan, "window_style", "USER_EXPLICIT")
            if _mass_in(entrance_omit_targets, comp):
                params["entrance_type"] = "none"
                params["entrance_requirement"] = "OMITTED"
                _tag_param_source(params, "entrance_type", "USER_EXPLICIT")
                if _covers_all_masses(masses, entrance_omit_targets):
                    _tag_plan_level_source(plan, "entrance_type", "USER_EXPLICIT")
            if _mass_in(roof_omit_targets, comp):
                params["roof_type"] = "none"
                params["roof_requirement"] = "OMITTED"
                _tag_param_source(params, "roof_type", "USER_EXPLICIT")
                if _covers_all_masses(masses, roof_omit_targets):
                    _tag_plan_level_source(plan, "roof_type", "USER_EXPLICIT")
            if _mass_in(courtyard_targets, comp):
                params["plan_type"] = "courtyard"
                _tag_param_source(params, "plan_type", "USER_EXPLICIT")
                if _covers_all_masses(masses, courtyard_targets):
                    _tag_plan_level_source(plan, "plan_type", "USER_EXPLICIT")
        kept.append(comp)

    for roof_type, targets in roof_targets.items():
        executable, extras = _executable_roof_and_extras(roof_type)
        allow_overwrite = _user_authorized_roof_overwrite(roof_reqs, roof_type, masses) or (
            _user_authorized_roof_overwrite(roof_reqs, executable, masses)
        )
        if (
            not allow_overwrite
            and targets
            and not _covers_all_masses(masses, targets)
        ):
            # Unique named-host bind from an unresolved player roof req may overwrite that host only.
            allow_overwrite = any(
                str(r.get("value") or "").strip().lower() in (roof_type, executable)
                and str(r.get("source") or "").lower() in ("user_explicit", "user", "player")
                for r in roof_reqs
            )
        _stamp_roof_on_targets(
            plan,
            kept,
            masses,
            targets,
            executable,
            allow_overwrite=allow_overwrite,
            roof_extras=extras,
        )

    plan["components"] = kept
    _promote_hard_scope_diagnostics(plan)
    return plan


# Composite planner tokens → executable RoofTypeCatalog token + eave construction params.
# Java executes double eaves via ``double_eave`` / ``eave_tiers``, not a fake roof_type.
_COMPOSITE_ROOF_FORMS: Dict[str, tuple[str, Dict[str, Any]]] = {
    "double_eave_xieshan": ("xieshan", {"double_eave": True, "eave_tiers": 2}),
    "double_eave_hip": ("hip", {"double_eave": True, "eave_tiers": 2}),
    "double_eave_xuanshan": ("xuanshan", {"double_eave": True, "eave_tiers": 2}),
}


def _executable_roof_and_extras(roof_type: str) -> tuple[str, Dict[str, Any]]:
    key = str(roof_type or "").strip().lower()
    if key in _COMPOSITE_ROOF_FORMS:
        return _COMPOSITE_ROOF_FORMS[key]
    return key, {}


def _promote_hard_scope_diagnostics(plan: Dict[str, Any]) -> Dict[str, Any]:
    """Unresolved hard scope diagnostics must block build — not only annotate plan_meta."""
    if not isinstance(plan, dict):
        return plan
    if str(plan.get("plan_status") or "").lower() == "capability_gap" or plan.get("capability_gap"):
        return plan
    meta = plan.get("plan_meta")
    if not isinstance(meta, dict):
        return plan
    diags = meta.get("scope_diagnostics")
    if not isinstance(diags, list) or not diags:
        return plan
    # Player hard constraints that could not bind safely must block build.
    # E_ROOF_SCOPE_AMBIGUOUS (unscoped multi-mass leave-AI-intact) stays annotate-only;
    # named-host failures use E_ROOF_HOST_UNRESOLVED instead.
    _BLOCKING = {
        "E_ROOF_APPLY_INCOMPLETE",
        "E_ROOF_HOST_UNRESOLVED",
        "E_ROOF_COMPONENT_INCONSISTENT",
        "E_OMIT_SCOPE_AMBIGUOUS",
        "E_HOST_UNRESOLVED",
    }
    hard = [
        d for d in diags
        if isinstance(d, dict) and str(d.get("code") or "") in _BLOCKING
    ]
    if not hard:
        return plan
    first = hard[0]
    plan["plan_status"] = "capability_gap"
    plan["capability_gap"] = {
        "code": str(first.get("code") or "E_SCOPE_UNRESOLVED"),
        "message": str(first.get("message") or "unresolved design scope constraint"),
        "path": "plan_meta.scope_diagnostics",
        "suggestions": [str(d.get("message") or d.get("code") or "") for d in hard[:8]],
    }
    return plan

def _looks_like_min_corner_anchor(rp: Dict[str, int], dims: Dict[str, int]) -> bool:
    hx = max(1, dims.get("width", 1)) // 2
    hz = max(1, dims.get("depth", 1)) // 2
    return abs((rp.get("x", 0) + hx)) <= 1 and abs((rp.get("z", 0) + hz)) <= 1


def _normalize_llm_plan_output(
    raw: Dict[str, Any],
    req: BuildRequest,
    *,
    building_profile: Optional[Any] = None,
) -> Dict[str, Any]:
    if not isinstance(raw, dict):
        return raw
    from .landmark_design_policy import reject_fixed_landmark_plan
    rejected = reject_fixed_landmark_plan(raw, building_profile)
    if rejected is not None:
        return rejected
    from .fbd_capabilities import is_fbd_only_plan
    if is_fbd_only_plan(raw):
        # Legacy enrichment changes geometry/authority and cannot inspect program bodies.
        from copy import deepcopy
        return deepcopy(raw)
    from .plan_semantic_gate import coerce_style_attribute_maps
    plan = coerce_style_attribute_maps(dict(raw))
    # Force player-relative world anchor. Scaffold/fallback used to emit (0,64,0),
    # which places buildings at world spawn (unloaded / invisible to the player).
    if req is not None and getattr(req, "player", None) is not None and getattr(req.player, "pos", None) is not None:
        try:
            player_anchor = {
                "x": int(req.player.pos.x),
                "y": int(req.player.pos.y),
                "z": int(req.player.pos.z),
            }
            existing = plan.get("anchor") if isinstance(plan.get("anchor"), dict) else None
            looks_world_spawn = (
                existing is None
                or (
                    int(existing.get("x", 0)) == 0
                    and int(existing.get("z", 0)) == 0
                    and abs(int(existing.get("y", 64)) - 64) <= 8
                )
            )
            # Always prefer player feet when plan still points at silent world spawn.
            if looks_world_spawn and (
                abs(player_anchor["x"]) > 0
                or abs(player_anchor["z"]) > 0
                or abs(player_anchor["y"] - 64) > 8
            ):
                plan["anchor"] = player_anchor
            elif existing is None:
                plan["anchor"] = player_anchor
        except (TypeError, ValueError, AttributeError):
            pass
    default_genome = BuildingGenome().model_dump()
    if req is not None:
        try:
            # Provide a minimal hint to topology based on selection/outline.
            if req.outline is not None:
                default_genome["constraints"]["insideSelectionOnly"] = True
        except Exception:
            pass

    if not isinstance(plan.get("genome"), dict):
        plan["genome"] = default_genome
    else:
        plan["genome"] = _sanitize_genome_dict(_deep_merge(default_genome, plan.get("genome", {})))

    layout = plan.get("layout")
    if isinstance(layout, dict):
        plan["layout"] = _sanitize_layout_slots(layout)

    components = plan.get("components")
    if isinstance(components, list):
        floors_hint = None
        is_chinese_style = False
        selection_dims = _selection_dimensions(req)
        selection_bounds = _selection_bounds_relative(req, plan.get("anchor") if isinstance(plan.get("anchor"), dict) else None)
        slot_anchors = {}
        layout = plan.get("layout")
        if isinstance(layout, dict):
            slots = layout.get("slots")
            if isinstance(slots, list):
                for slot in slots:
                    if not isinstance(slot, dict):
                        continue
                    slot_id = str(slot.get("slot_id") or "").strip()
                    if not slot_id:
                        continue
                    slot_anchors[slot_id] = _normalize_vec3(slot.get("anchor"))
        if req is not None:
            user_text = req.userMessage or ""
            floors_hint = _parse_levels_from_text(user_text)
            from .building_contract import extract_requirements
            floor_requirements = [r for r in extract_requirements(user_text) if r['property'] == 'floor_count']
            if floor_requirements and not any(r['scope'] == 'all_main_masses' for r in floor_requirements):
                floors_hint = None  # A named residence's floors do not belong to its chapel or workshop.
        style_profile = str(plan.get("style_profile") or "").lower()
        cultural = (plan.get("genome") or {}).get("culturalStyle") or {}
        region = str(cultural.get("region") or "").lower()
        is_chinese_style = ("chinese" in style_profile or "hui" in style_profile
                            or "chinese" in region or "hui" in region)
        filtered_components = []
        mass_dims = _extract_mass_dims(components)
        for comp in components:
            if not isinstance(comp, dict):
                continue
            ctype = _normalize_component_type(comp.get("component_type"))
            if not ctype:
                continue
            comp["component_type"] = ctype
            comp["_style_hint"] = str(plan.get("style_profile") or "")
            comp["features"] = _normalize_features(comp.get("features"))
            params = comp.get("params")
            if not isinstance(params, dict):
                params = {}
            _fill_component_params(params, comp, plan.get("genome", {}))
            comp["params"] = params
            comp["relative_position"] = _normalize_vec3(comp.get("relative_position"))
            dims = _normalize_dimensions(comp.get("dimensions"), ctype, params, mass_dims)
            comp["dimensions"] = dims
            if ctype in ("MASS_MAIN", "MASS_SECONDARY", "MASS_WING", "SIDE_WING", "MAIN_MASS"):
                if ctype == "MASS_MAIN" and not _param_string(params, "anchor_mode", "anchorMode"):
                    rp = comp.get("relative_position") or {}
                    if _looks_like_min_corner_anchor(rp, dims):
                        params["anchor_mode"] = "min_corner"
                        rp["x"] = -(dims["width"] // 2)
                        rp["z"] = -(dims["depth"] // 2)
                        comp["relative_position"] = rp
                # TECHNICAL_DEFAULT only when roof_type is absent — never overwrite AI-planned roofs.
                if is_chinese_style and not str(params.get("roof_type") or "").strip():
                    params["roof_type"] = "xuanshan"
                    _tag_param_source(params, "roof_type", "TECHNICAL_DEFAULT")
                _tag_present_param_as_ai_planned(params, "roof_type", "plan_type", "courtyard_ratio")
            elif ctype == "ROOF":
                if is_chinese_style and not str(params.get("roof_type") or "").strip():
                    params["roof_type"] = "xuanshan"
                    _tag_param_source(params, "roof_type", "TECHNICAL_DEFAULT")
                _tag_present_param_as_ai_planned(params, "roof_type")
            elif ctype == "COURTYARD":
                _tag_present_param_as_ai_planned(params, "plan_type", "courtyard_ratio")
            if floors_hint:
                if ctype in ("MASS_MAIN", "MASS_SECONDARY", "MASS_WING", "SIDE_WING", "MAIN_MASS"):
                    params.setdefault("floor_count", floors_hint)
                    params.setdefault("floor_height", 3)
                    dims = comp.get("dimensions") or {}
                    try:
                        height = int(dims.get("height", 0))
                    except Exception:
                        height = 0
                    min_height = max(3, int(params.get("floor_height", 3)) * int(params.get("floor_count", floors_hint)))
                    if height < min_height:
                        dims["height"] = min_height
                        comp["dimensions"] = dims
            dims = _clamp_dimensions_to_selection(comp.get("dimensions") or dims, selection_dims)
            comp["dimensions"] = dims
            anchor_mode = _param_string(params, "anchor_mode", "anchorMode")
            if not anchor_mode:
                if ctype.startswith("TOWER"):
                    anchor_mode = "center"
                elif ctype in _MASS_COMPONENT_TYPES:
                    anchor_mode = "center"
                else:
                    anchor_mode = "min_corner"
            slot_anchor = slot_anchors.get(str(comp.get("slot_id") or ""), {"x": 0, "y": 0, "z": 0})
            rp = comp.get("relative_position") or {"x": 0, "y": 0, "z": 0}
            effective_rp = {
                "x": int(rp.get("x", 0)) + int(slot_anchor.get("x", 0)),
                "y": int(rp.get("y", 0)) + int(slot_anchor.get("y", 0)),
                "z": int(rp.get("z", 0)) + int(slot_anchor.get("z", 0)),
            }
            effective_rp = _clamp_relative_position(effective_rp, dims, anchor_mode, selection_bounds)
            comp["relative_position"] = {
                "x": effective_rp["x"] - int(slot_anchor.get("x", 0)),
                "y": effective_rp["y"] - int(slot_anchor.get("y", 0)),
                "z": effective_rp["z"] - int(slot_anchor.get("z", 0)),
            }
            if mass_dims is None and ctype in _MASS_COMPONENT_TYPES:
                dims = comp.get("dimensions") or dims
                if isinstance(dims, dict):
                    mass_dims = {
                        "width": int(dims.get("width", 8)),
                        "depth": int(dims.get("depth", 6)),
                        "height": int(dims.get("height", 6)),
                    }
            filtered_components.append(comp)
        plan["components"] = filtered_components
        _sanitize_landmark_modules(plan, req, building_profile)

    gc = plan.get("global_constraints")
    if isinstance(gc, dict):
        gc = dict(gc)
        sym = _normalize_global_symmetry_value(
            gc.get("symmetry"),
            genome=plan.get("genome") if isinstance(plan.get("genome"), dict) else None,
        )
        if sym:
            gc["symmetry"] = sym
        elif "symmetry" in gc:
            gc.pop("symmetry", None)
        if gc.get("facing") is not None:
            facing = str(gc["facing"]).strip().upper()
            facing = {
                "N": "NORTH", "S": "SOUTH", "E": "EAST", "W": "WEST",
                "北": "NORTH", "南": "SOUTH", "东": "EAST", "西": "WEST",
                "NONE": "SOUTH", "ANY": "SOUTH", "DEFAULT": "SOUTH",
            }.get(facing, facing)
            if facing in ("NORTH", "SOUTH", "EAST", "WEST"):
                gc["facing"] = facing
            else:
                gc.pop("facing", None)
        plan["global_constraints"] = gc

    if building_profile is not None:
        try:
            from .plan_specificity_guard import apply_profile_features_to_plan

            plan = apply_profile_features_to_plan(plan, building_profile)
        except Exception as exc:
            logger.warning("Plan distinguishing_features sync skipped: %s", exc)

    from .building_contract import apply_building_contract, request_text
    contract_text = request_text(req)
    plan = apply_building_contract(plan, contract_text)

    try:
        from .plan_architectural_enrichment import enrich_llm_plan_architectural_detail

        user_text = (req.userMessage or "").strip() if req is not None else ""
        plan = enrich_llm_plan_architectural_detail(
            plan,
            user_text=user_text,
            profile=building_profile,
        )
        if building_profile is not None:
            from .building_plan_stage import score_plan_profile_alignment

            alignment = score_plan_profile_alignment(plan, building_profile)
            logger.info(
                "Plan-profile alignment passed=%s coverage=%.2f matched=%s missing=%s guard=%s",
                alignment["passed"],
                alignment["feature_coverage"],
                alignment["feature_matched"],
                alignment["feature_missing"],
                plan.get("enrichment_guard"),
            )
    except Exception as exc:
        logger.warning("Architectural plan enrichment skipped: %s", exc)

    if isinstance(plan.get("components"), list):
        for comp in plan["components"]:
            if isinstance(comp, dict):
                comp.pop("_style_hint", None)

    if building_profile is not None:
        fidelity_msg = getattr(building_profile, "fidelity_message_zh", None)
        meta = plan.get("plan_meta") if isinstance(plan.get("plan_meta"), dict) else {}
        preserve_notice = bool(meta.get("preserve_fidelity_notice") or meta.get("scaffolded_from_request_intent"))
        if fidelity_msg and str(fidelity_msg).strip() and not preserve_notice:
            plan["player_fidelity_notice_zh"] = str(fidelity_msg).strip()

    plan = apply_building_contract(plan, contract_text)
    try:
        explicit_text = (req.userMessage or "").strip() if req is not None else (contract_text or "")
        plan = _apply_user_explicit_design_sources(plan, explicit_text or contract_text or "")
    except Exception as exc:
        logger.warning("USER_EXPLICIT design_sources stamp skipped: %s", exc)
    try:
        from .generation_capability import apply_stage_b_capability_repair

        user_text = (req.userMessage or "").strip() if req is not None else contract_text
        plan, cap_errors = apply_stage_b_capability_repair(plan, user_text or "")
        if cap_errors:
            logger.info("Stage-B capability remaining issues: %s", cap_errors[:5])
    except Exception as exc:
        logger.warning("Stage-B capability repair skipped: %s", exc)
    if building_profile is not None:
        from .style_feature_compiler import apply_style_feature_defaults
        plan = apply_style_feature_defaults(plan, building_profile)
        feature_report = plan["proportion_hints"]["style_feature_report"]
        logger.info("Style feature parameter verification: %s", feature_report)
    try:
        plan = _ensure_compiler_satellite_policy(plan)
    except Exception as exc:
        logger.warning("compiler satellite policy stamp skipped: %s", exc)
    return reject_fixed_landmark_plan(plan, building_profile) or plan
