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


_COURTYARD_LITERALS = (
    "courtyard", "siheyuan", "中庭", "庭院", "四合院", "院落", "内院",
)


def _user_explicit_courtyard(text: str) -> bool:
    from .planning_request_hints import _keyword_hit_unnegated
    return _keyword_hit_unnegated((text or "").lower(), _COURTYARD_LITERALS)


def _apply_user_explicit_design_sources(plan: Dict[str, Any], user_text: str) -> Dict[str, Any]:
    """Stamp USER_EXPLICIT provenance for player-stated design constraints and omissions.

    User constraints outrank AI_PLANNED / TECHNICAL_DEFAULT. Explicit omissions
    (无屋顶 / 不要窗 / 不要门) mark MASS params as ``none`` and drop conflicting satellites.
    Explicit courtyard language locks ``plan_type=courtyard``.
    """
    if not isinstance(plan, dict):
        return plan
    from .request_intent import extract_request_intent
    from .building_contract import extract_requirements

    text = (user_text or "").strip()
    intent = extract_request_intent(text) if text else {}
    forbids = {str(x).strip().lower() for x in (intent.get("forbids") or []) if x}
    explicit_roof = _resolve_user_explicit_roof(text)
    explicit_courtyard = _user_explicit_courtyard(text)
    for requirement in extract_requirements(text) if text else []:
        prop = str(requirement.get("property") or "")
        value = requirement.get("value")
        source = str(requirement.get("source") or "").lower()
        if source not in ("user_explicit", "user", "player"):
            continue
        if prop == "roof_type" and value == "none":
            explicit_roof = "none"
        elif prop == "window_style" and value == "none":
            forbids.add("windows")
        elif prop == "entrance_type" and value == "none":
            forbids.add("entrance")

    if not explicit_roof and not forbids and not explicit_courtyard:
        return plan

    components = plan.get("components")
    if not isinstance(components, list):
        return plan

    omit_roof = explicit_roof == "none"
    omit_windows = "windows" in forbids
    omit_entrance = "entrance" in forbids
    design_req_updates: Dict[str, str] = {}
    if omit_roof:
        design_req_updates["roof"] = "OMITTED"
    if omit_windows:
        design_req_updates["facade"] = "OMITTED"
    if omit_entrance:
        design_req_updates["entrance"] = "OMITTED"
    _merge_design_requirements(plan, design_req_updates)

    kept: list = []
    for comp in components:
        if not isinstance(comp, dict):
            continue
        ctype = str(comp.get("component_type") or "").upper()
        if omit_roof and ctype in ("ROOF", "ROOF_STRUCTURE"):
            continue
        if omit_windows and ctype in ("FACADE_WINDOWS", "FACADE"):
            continue
        if omit_entrance and ctype == "ENTRANCE":
            continue

        params = comp.get("params")
        if not isinstance(params, dict):
            params = {}
            comp["params"] = params

        if ctype in (
            "MASS_MAIN", "MASS_SECONDARY", "MASS_WING", "SIDE_WING", "MAIN_MASS", "ROOF",
        ):
            if explicit_roof:
                if str(params.get("roof_type") or "").strip().lower() != explicit_roof:
                    params["roof_type"] = explicit_roof
                _tag_param_source(params, "roof_type", "USER_EXPLICIT")
                _tag_plan_level_source(plan, "roof_type", "USER_EXPLICIT")
            if omit_windows and ctype != "ROOF":
                params["window_style"] = "none"
                params["window_ratio"] = 0
                _tag_param_source(params, "window_style", "USER_EXPLICIT")
                _tag_param_source(params, "window_ratio", "USER_EXPLICIT")
                _tag_plan_level_source(plan, "window_style", "USER_EXPLICIT")
            if omit_entrance and ctype != "ROOF":
                params["entrance_type"] = "none"
                _tag_param_source(params, "entrance_type", "USER_EXPLICIT")
                _tag_plan_level_source(plan, "entrance_type", "USER_EXPLICIT")
            if explicit_courtyard and ctype != "ROOF":
                if str(params.get("plan_type") or "").strip().lower() != "courtyard":
                    params["plan_type"] = "courtyard"
                _tag_param_source(params, "plan_type", "USER_EXPLICIT")
                _tag_plan_level_source(plan, "plan_type", "USER_EXPLICIT")
        elif ctype == "COURTYARD" and explicit_courtyard:
            _tag_param_source(params, "plan_type", "USER_EXPLICIT")
        kept.append(comp)
    plan["components"] = kept
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
    return reject_fixed_landmark_plan(plan, building_profile) or plan
