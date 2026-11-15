"""
LlmPlan schema validation repair and graceful degradation.

Open-world builds should not 502 when the LLM emits slightly invalid JSON;
prefer sanitize → bounded LLM repair → profile fallback → capability_gap.
"""
from __future__ import annotations

import json
import logging
import os
from typing import Any, Callable, Dict, List, Optional

from ..models.building_profile import BuildingProfile
from ..models.llm_plan import LlmPlanValidationError, validate_llm_plan_dict
from ..models.request import BuildRequest
from .plan_consistency import component_consistency_errors, resolve_delegated_entrances

logger = logging.getLogger(__name__)

LLMPLAN_VALIDATION_REPAIR_LLM = (os.getenv("LLMPLAN_VALIDATION_REPAIR_LLM") or "on").strip().lower() not in (
    "off",
    "0",
    "false",
    "no",
)


def build_validation_capability_gap(
    errors: List[str],
    *,
    profile: Optional[BuildingProfile] = None,
) -> Dict[str, Any]:
    suggestions = list(errors[:4])
    if profile is not None and not profile.minecraft_strategy.landmark_module:
        raw_rec = getattr(profile.minecraft_strategy, "recommended_components", None)
        rec = (
            [str(c).upper() for c in raw_rec if c]
            if isinstance(raw_rec, (list, tuple))
            else []
        )
        model_designed = (
            getattr(profile.minecraft_strategy, "model_designed_landmark", False) is True
        )
        if model_designed:
            if rec:
                suggestions.append(
                    "Model-designed landmark: repair the original compositional plan using "
                    f"recommended_components=[{', '.join(rec[:6])}]; "
                    "do not substitute typology_id / MODULE / whole-building presets."
                )
            else:
                suggestions.append(
                    "Model-designed landmark: fix missing fields only; "
                    "do not substitute typology_id / MODULE / residential MASS_MAIN kits."
                )
        elif rec:
            suggestions.append(
                f"Compose with {', '.join(rec[:6])} from BuildingProfile.recommended_components; "
                "do not use landmark MODULE presets."
            )
        else:
            suggestions.append(
                "Compose with MASS_MAIN / ROOF / ENTRANCE from BuildingProfile.recommended_components; "
                "do not use landmark MODULE presets."
            )
    suggestions.extend([
        "Ensure global_constraints.symmetry is NONE|MIRROR_X|MIRROR_Z|RADIAL.",
        "Ensure layout.slots[].slot_id and anchor are present.",
        "Ensure genome.symmetry.mirror is boolean or omitted.",
    ])
    first = errors[0] if errors else "LlmPlan validation failed"
    return {
        "code": "E_LLMPLAN_VALIDATION",
        "message": first,
        "path": "plan",
        "suggestions": suggestions[:6],
    }


def _anchor_from_request(req: Optional[BuildRequest]) -> Optional[Dict[str, int]]:
    """Player feet position — never silent world (0,64,0)."""
    if req is None:
        return None
    player = getattr(req, "player", None)
    if player is None and isinstance(req, dict):
        player = req.get("player")
    pos = None
    if player is not None:
        pos = getattr(player, "pos", None)
        if pos is None and isinstance(player, dict):
            pos = player.get("pos") or player.get("playerPos")
    if pos is None:
        # Some adapters put playerPos on the request root.
        pos = getattr(req, "playerPos", None)
        if pos is None and isinstance(req, dict):
            pos = req.get("playerPos")
    if pos is None:
        return None
    try:
        if isinstance(pos, dict):
            return {"x": int(pos["x"]), "y": int(pos["y"]), "z": int(pos["z"])}
        return {"x": int(pos.x), "y": int(pos.y), "z": int(pos.z)}
    except (TypeError, ValueError, AttributeError, KeyError):
        return None


_FALLBACK_SKELETONS = frozenset({
    "LINEAR_PATH",
    "RADIAL_RING",
    "RADIAL_SPOKE",
    "GRID",
    "GRID_BAY",
    "COMPOUND",
    "COURTYARD",
    "PATH_POLYLINE",
    "SPAN_SUSPENSION",
    "VERTICAL_TAPER",
    "VERTICAL_STACK",
    "PERIMETER_LOOP",
    "ENCLOSURE",
    "LINEAR",  # legacy alias normalized later
})


_VALID_FACINGS = frozenset({"NORTH", "SOUTH", "EAST", "WEST"})
_VALID_SYMMETRIES = frozenset({"NONE", "MIRROR_X", "MIRROR_Z", "RADIAL"})
_VALID_TERRAIN = frozenset({"PRESERVE", "ADAPTIVE", "TERRACE", "FLATTEN"})


def _constraints_from_request(
    req: Optional[BuildRequest],
    *,
    prior_plan: Optional[Dict[str, Any]] = None,
    user_text: str = "",
) -> Dict[str, str]:
    """Preserve player-facing / symmetry / terrain hard constraints in fallbacks."""
    facing = "SOUTH"
    symmetry = "NONE"
    terrain_strategy = "ADAPTIVE"
    facing_from_req = False
    symmetry_from_req = False
    terrain_from_prior = False

    prior_gc = {}
    if isinstance(prior_plan, dict):
        raw_gc = prior_plan.get("global_constraints")
        if isinstance(raw_gc, dict):
            prior_gc = raw_gc

    # 1) Prefer structured BuildRequest hard constraints.
    if req is not None:
        player = getattr(req, "player", None)
        if player is None and isinstance(req, dict):
            player = req.get("player")
        raw_facing = None
        if player is not None:
            raw_facing = getattr(player, "facing", None)
            if raw_facing is None and isinstance(player, dict):
                raw_facing = player.get("facing")
        if isinstance(raw_facing, str) and raw_facing.strip():
            token = raw_facing.strip().upper()
            token = {
                "N": "NORTH", "S": "SOUTH", "E": "EAST", "W": "WEST",
                "北": "NORTH", "南": "SOUTH", "东": "EAST", "西": "WEST",
            }.get(token, token)
            if token in _VALID_FACINGS:
                facing = token
                facing_from_req = True

        raw_sym = getattr(req, "symmetry", None)
        if raw_sym is None and isinstance(req, dict):
            raw_sym = req.get("symmetry")
        mode = None
        if raw_sym is not None:
            mode = getattr(raw_sym, "mode", None)
            if mode is None and isinstance(raw_sym, dict):
                mode = raw_sym.get("mode")
        if isinstance(mode, str) and mode.strip():
            token = mode.strip().upper().replace("-", "_")
            if token in _VALID_SYMMETRIES:
                symmetry = token
                symmetry_from_req = True
            elif token in {"MIRROR_Y", "BOTH", "CUSTOM_AXIS"}:
                # LlmPlan global_constraints only accepts the closed enum above.
                symmetry = "MIRROR_X"
                symmetry_from_req = True

    # 2) Fill gaps from the failed plan's authored constraints (never override request).
    if not facing_from_req:
        prior_facing = str(prior_gc.get("facing") or "").strip().upper()
        if prior_facing in _VALID_FACINGS:
            facing = prior_facing
    if not symmetry_from_req:
        prior_sym = str(prior_gc.get("symmetry") or "").strip().upper().replace("-", "_")
        if prior_sym in _VALID_SYMMETRIES:
            symmetry = prior_sym
    prior_terrain = str(prior_gc.get("terrain_strategy") or "").strip().upper()
    if prior_terrain in _VALID_TERRAIN:
        terrain_strategy = prior_terrain
        terrain_from_prior = True

    # 3) Light user-text terrain cues when nothing was authored.
    if not terrain_from_prior and terrain_strategy == "ADAPTIVE" and user_text:
        text = user_text.lower()
        if any(t in text for t in ("flatten", "平整", "夷平", "推平")):
            terrain_strategy = "FLATTEN"
        elif any(t in text for t in ("preserve terrain", "preserve", "保留地形", "不改地形", "贴地")):
            terrain_strategy = "PRESERVE"
        elif any(t in text for t in ("terrace", "梯田", "台地")):
            terrain_strategy = "TERRACE"

    return {
        "facing": facing,
        "symmetry": symmetry,
        "terrain_strategy": terrain_strategy,
    }


def build_minimal_plan_from_profile(
    profile: BuildingProfile,
    req: Optional[BuildRequest],
    user_text: str,
    *,
    prior_plan: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """Deterministic compositional fallback when LLM output cannot validate.

    Not used for model-designed landmarks (those must repair or capability_gap).
    """
    sh = profile.scale_hints
    width = int(sh.typical_width_blocks or 32)
    depth = int(sh.typical_depth_blocks or max(16, width * 3 // 4))
    height = int(sh.typical_height_blocks or 16)
    width = max(8, min(width, 128))
    depth = max(8, min(depth, 128))
    height = max(6, min(height, 96))

    # Never invent silent world spawn — leave unset so normalize injects player.pos.
    anchor = _anchor_from_request(req)
    constraints = _constraints_from_request(
        req, prior_plan=prior_plan, user_text=user_text or ""
    )

    style_profile = profile.identity.style or "DEFAULT"
    skeleton = (profile.minecraft_strategy.skeleton_type or "COMPOUND").upper()
    lm = profile.minecraft_strategy.landmark_module
    st = profile.minecraft_strategy.structural_typology
    ref = profile.minecraft_strategy.reference_landmark

    components: List[Dict[str, Any]] = []
    if st:
        from .typology_plan_repair import _typology_params

        params = _typology_params(st, ref, {})
        components.append(
            {
                "component_type": "STRUCTURE",
                "relative_position": {"x": 0, "y": 0, "z": 0},
                "dimensions": {"width": width, "depth": depth, "height": height},
                "features": [f"typology:{st}"],
                "params": params,
            }
        )
    elif lm:
        components.append(
            {
                "component_type": "MODULE",
                "relative_position": {"x": 0, "y": 0, "z": 0},
                "dimensions": {"width": width, "depth": depth, "height": height},
                "features": [f"landmark:{lm}"],
                "params": {"module_id": lm},
            }
        )
    else:
        recommended = [
            str(c).upper()
            for c in (profile.minecraft_strategy.recommended_components or [])
            if str(c).upper() != "MODULE"
        ]
        if not recommended:
            recommended = ["MASS_MAIN", "ROOF", "ENTRANCE"]
        y_offset = 0
        for idx, ctype in enumerate(recommended[:4]):
            comp_height = height if ctype.startswith("MASS") or ctype == "TOWER" else max(3, height // 4)
            comp_depth = depth if not ctype.startswith("FACADE") else 1
            components.append(
                {
                    "component_type": ctype,
                    "relative_position": {"x": 0, "y": y_offset, "z": 0},
                    "dimensions": {
                        "width": width,
                        "depth": comp_depth,
                        "height": comp_height,
                    },
                    "features": [],
                    "params": {},
                }
            )
            if ctype.startswith("MASS") or ctype == "TOWER":
                y_offset = 0

    plan: Dict[str, Any] = {
        "mode": "build",
        "style_profile": style_profile,
        "global_constraints": dict(constraints),
        "layout": {
            "skeleton_type": skeleton if skeleton in _FALLBACK_SKELETONS else "COMPOUND",
            "path_based": False,
            "slots": [],
        },
        "components": components,
        "genome": {
            "genomeVersion": "1.0",
            "symmetry": {
                "type": "radial" if constraints["symmetry"] == "RADIAL" else (
                    "none" if constraints["symmetry"] == "NONE" else "bilateral"
                ),
                "order": None,
                "mirror": constraints["symmetry"] in {"MIRROR_X", "MIRROR_Z"},
            },
        },
    }
    if anchor is not None:
        plan["anchor"] = anchor
    notes = profile.research_notes or user_text[:200]
    if notes:
        plan["_degraded_from_profile"] = notes[:400]
    return plan


def emit_validation_gap(
    plan: Dict[str, Any],
    errors: List[str],
    profile: Optional[BuildingProfile],
) -> Dict[str, Any]:
    gap = build_validation_capability_gap(errors, profile=profile)
    out = dict(plan) if isinstance(plan, dict) else {}
    out["plan_status"] = "capability_gap"
    out["error"] = gap["message"]
    out["capability_gap"] = gap
    logger.warning(
        "LlmPlan validation unresolved; emitting capability_gap: %s",
        gap.get("message"),
    )
    return out


def _try_validate(normalized: Dict[str, Any], user_text: str = "") -> Optional[List[str]]:
    try:
        from .generation_capability import apply_stage_b_capability_repair

        _, cap_errors = apply_stage_b_capability_repair(normalized, user_text or "")
        validate_llm_plan_dict(normalized)
        from .plan_semantic_gate import assert_plan_semantics

        assert_plan_semantics(normalized, user_text or "")
        consistency = component_consistency_errors(normalized) or []
        combined = list(cap_errors or []) + list(consistency)
        return combined or None
    except LlmPlanValidationError as exc:
        return list(exc.errors)


def _record_quality(
    plan: Dict[str, Any],
    *,
    user_text: str,
    parse_ok: bool,
    fidelity_ok: bool,
    reason: str = "",
) -> None:
    try:
        from .generation_quality_metrics import record_plan_outcome, summarize_plan
        from .plan_semantic_gate import detect_semantic_intent

        record_plan_outcome(
            parse_ok=parse_ok,
            build_ok=parse_ok and fidelity_ok,  # Python stage has no placement yet
            intent_fidelity_ok=fidelity_ok,
            request_id="",
            user_text=user_text or "",
            intent_class=detect_semantic_intent(user_text or "", plan if isinstance(plan, dict) else None),
            style_profile=str((plan or {}).get("style_profile") or ""),
            reason=reason,
            plan_summary=summarize_plan(plan if isinstance(plan, dict) else None),
        )
    except Exception:
        logger.debug("generation quality metrics recording skipped", exc_info=True)


def repair_llm_plan_validation(
    plan: Dict[str, Any],
    req: Optional[BuildRequest],
    *,
    user_text: str,
    building_profile: Optional[BuildingProfile],
    normalize_fn: Callable[..., Dict[str, Any]],
    client: Any = None,
    model: Optional[str] = None,
    messages: Optional[List[Dict[str, str]]] = None,
    call_with_timeout: Any = None,
    timeout_sec: float = 45.0,
    prefer_json_object: bool = False,
) -> Dict[str, Any]:
    """
    Validate, attempt at most two LLM repairs, then degrade to profile or capability_gap.
    Each completion is capped at 4096 tokens and 45 seconds per API attempt.
    Never raises LlmPlanValidationError.
    """
    from .fbd_capabilities import has_fbd_program, requests_fbd
    from .generation_capability import apply_stage_b_capability_repair
    preserve_fbd = has_fbd_program(plan) or requests_fbd(user_text)
    strategy = (
        getattr(building_profile, "minecraft_strategy", None)
        if building_profile is not None
        else None
    )
    model_designed = (
        getattr(strategy, "model_designed_landmark", False) is True
        if strategy is not None
        else False
    )
    normalized = resolve_delegated_entrances(normalize_fn(plan, req), user_text)
    normalized, _cap = apply_stage_b_capability_repair(normalized, user_text or "")
    preserve_design = bool(component_consistency_errors(normalized))
    errors = _try_validate(normalized, user_text)
    if preserve_fbd and not has_fbd_program(normalized) and normalized.get('plan_status') != 'capability_gap':
        errors = ["FBD request requires ASSEMBLY.params.fbd_program; preserve procedural authority, do not substitute legacy geometry"]
    if not errors:
        _record_quality(normalized, user_text=user_text, parse_ok=True, fidelity_ok=True, reason="ok")
        return normalized

    logger.warning("LlmPlan validation failed (%d issues), attempting recovery: %s", len(errors), errors[:3])

    if (
        LLMPLAN_VALIDATION_REPAIR_LLM
        and client is not None
        and model
        and messages
        and call_with_timeout is not None
    ):
        from ..models.llm_plan_json_schema import call_chat_with_llm_plan_response_formats

        for attempt in range(1, 3):
            try:
                if model_designed:
                    rec = []
                    if strategy is not None:
                        raw_rec = getattr(strategy, "recommended_components", None)
                        if isinstance(raw_rec, (list, tuple)):
                            rec = [str(c).upper() for c in raw_rec if c]
                    morph_hint = (
                        "If the error mentions tower/pagoda morphology: repair the original "
                        "compositional massing (TOWER / TOWER_* / ROOF tiers) in place. "
                        "Do NOT emit params.typology_id, MODULE, or whole-building presets — "
                        "model-designed landmarks forbid fixed typology executors.\n"
                    )
                    if rec:
                        morph_hint += (
                            f"Prefer recommended_components=[{', '.join(rec[:6])}].\n"
                        )
                else:
                    morph_hint = (
                        "If the error mentions tower/pagoda morphology: emit ONE STRUCTURE "
                        "component with params.typology_id=dense_eaves_pagoda "
                        "(or tibetan_stupa for 藏式/覆钵), floor_count from the user text, "
                        "and wall_block/roof_block matching the requested style. "
                        "Do NOT emit a residential MASS_MAIN+gable house kit for 佛塔/古塔/宝塔.\n"
                    )
                repair_user = (
                    "Your previous LlmPlan JSON failed validation. Fix the reported issues.\n"
                    "Errors:\n- "
                    + "\n- ".join(errors[:8])
                    + "\n\n"
                    + morph_hint
                    + "Keep the plan anchor at the player position already provided; do not reset to (0,64,0).\n"
                    "Return a single corrected LlmPlan JSON object."
                )
                repair_messages = list(messages)
                repair_messages.append({"role": "assistant", "content": json.dumps(normalized, ensure_ascii=False)})
                repair_messages.append({"role": "user", "content": repair_user})
                response, _fmt = call_chat_with_llm_plan_response_formats(
                    client,
                    model=model,
                    messages=repair_messages,
                    temperature=0.2,
                    call_with_timeout=call_with_timeout,
                    timeout_sec=min(timeout_sec, 45.0),
                    max_tokens=4096,
                    prefer_json_object=prefer_json_object,
                )
                raw = response.choices[0].message.content
                if not raw or not str(raw).strip():
                    raise ValueError("Empty LlmPlan repair response")
                repaired = json.loads(raw)
                if not isinstance(repaired, dict):
                    raise ValueError("LlmPlan repair response must be an object")
                repaired_norm = resolve_delegated_entrances(normalize_fn(repaired, req), user_text)
                preserve_design = preserve_design or bool(component_consistency_errors(repaired_norm))
                repaired_errors = _try_validate(repaired_norm, user_text)
                if preserve_fbd and not has_fbd_program(repaired_norm) and repaired_norm.get('plan_status') != 'capability_gap':
                    repaired_errors = ["FBD repair must preserve procedural authority; do not replace the program with another building"]
                if not repaired_errors:
                    logger.info("LlmPlan recovered via LLM validation repair")
                    _record_quality(
                        repaired_norm, user_text=user_text, parse_ok=True, fidelity_ok=True, reason="repaired"
                    )
                    return repaired_norm
                normalized, errors = repaired_norm, repaired_errors
            except (TypeError, ValueError):
                logger.exception("LlmPlan repair round %d returned invalid data", attempt)
            except Exception:
                logger.exception("LlmPlan repair round %d failed; stopping remote repair", attempt)
                break

    # Directed re-plan from high-confidence request intent before emitting a hard gap.
    # Only scaffold when keyword/LLM merge is confident — never replace a plausible LLM
    # plan because of a weak lexical false positive (重檐→塔, 庭院→园林).
    # Model-designed landmarks: never swap the whole design for a deterministic template.
    def _is_schema_field_fail(err: object) -> bool:
        e = str(err).lower()
        if "missing field" in e or "field required" in e:
            return True
        if "facing" in e and ("literal" in e or "input should be" in e or "enum" in e or "type_error" in e):
            return True
        if "anchor" in e and ("required" in e or "missing" in e or "type_error" in e or "dict" in e):
            return True
        return False

    semantic_fail = any(
        ("semantics" in str(e).lower()) or ("tower/pagoda" in str(e).lower())
        or ("empty components" in str(e).lower()) or ("components:" in str(e).lower())
        or _is_schema_field_fail(e)
        for e in (errors or [])
    )
    if semantic_fail and not preserve_fbd and not model_designed:
        try:
            from .request_intent import (
                attach_request_intent,
                resolve_request_intent,
                scaffold_plan_for_intent,
            )

            intent = resolve_request_intent(user_text or "", normalized)
            anchor = _anchor_from_request(req)
            # Prefer soft morph that keeps LLM style/materials/dims over identical hard templates.
            if intent.get("scaffold_eligible") is True and anchor is not None:
                from .request_intent import morph_failed_plan_toward_intent

                scaffold = morph_failed_plan_toward_intent(
                    normalized, intent, anchor=anchor
                ) or scaffold_plan_for_intent(
                    intent, anchor=anchor, base_plan=normalized
                )
                if scaffold is not None:
                    reason_zh = "；".join(str(e) for e in (errors or [])[:2])
                    cls = str(intent.get("building_class") or "")
                    notice = (
                        f"原计划未通过语义校验，已按「{cls}」确定性模板重建"
                        f"（保留风格/材质线索）。原因：{reason_zh}"
                    )
                    # Keep scaffold notice; do not let later profile overwrite silently.
                    scaffold["player_fidelity_notice_zh"] = notice
                    meta = scaffold.get("plan_meta") if isinstance(scaffold.get("plan_meta"), dict) else {}
                    meta = dict(meta)
                    meta["scaffold_reason"] = reason_zh
                    meta["scaffolded_from_request_intent"] = True
                    meta["preserve_fidelity_notice"] = True
                    scaffold["plan_meta"] = meta
                    # Ensure anchor survives normalize.
                    scaffold["anchor"] = dict(anchor)
                    scaffold_norm = resolve_delegated_entrances(
                        attach_request_intent(normalize_fn(scaffold, req), user_text or ""),
                        user_text or "",
                    )
                    # Re-assert player anchor after normalize (must not drift to 0,64,0).
                    scaffold_norm["anchor"] = dict(anchor)
                    if isinstance(scaffold_norm.get("plan_meta"), dict):
                        scaffold_norm["plan_meta"]["preserve_fidelity_notice"] = True
                    if not scaffold_norm.get("player_fidelity_notice_zh"):
                        scaffold_norm["player_fidelity_notice_zh"] = notice
                    scaffold_errors = _try_validate(scaffold_norm, user_text)
                    if not scaffold_errors:
                        logger.info(
                            "LlmPlan recovered via request_intent scaffold class=%s conf=%s anchor=%s",
                            intent.get("building_class"),
                            intent.get("confidence"),
                            anchor,
                        )
                        _record_quality(
                            scaffold_norm,
                            user_text=user_text,
                            parse_ok=True,
                            fidelity_ok=True,
                            reason="request_intent_scaffold",
                        )
                        return scaffold_norm
            else:
                logger.info(
                    "Skipping request_intent scaffold (class=%s conf=%s eligible=%s anchor=%s)",
                    intent.get("building_class"),
                    intent.get("confidence"),
                    intent.get("scaffold_eligible"),
                    anchor,
                )
        except Exception:
            logger.exception("request_intent scaffold recovery failed")
    elif model_designed:
        logger.info(
            "Skipping deterministic scaffold/profile fallback for model-designed landmark"
        )

    if (
        building_profile is not None
        and not preserve_fbd
        and not preserve_design
        and not model_designed
    ):
        fallback = build_minimal_plan_from_profile(
            building_profile, req, user_text, prior_plan=normalized
        )
        fallback_norm = normalize_fn(fallback, req)
        fallback_errors = _try_validate(fallback_norm, user_text)
        if not fallback_errors:
            logger.info("LlmPlan recovered via BuildingProfile compositional fallback")
            _record_quality(
                fallback_norm, user_text=user_text, parse_ok=True, fidelity_ok=True, reason="profile_fallback"
            )
            return fallback_norm

    gap_plan = emit_validation_gap(normalized, errors, building_profile)
    _record_quality(
        gap_plan,
        user_text=user_text,
        parse_ok=False,
        fidelity_ok=False,
        reason=(errors[0] if errors else "capability_gap"),
    )
    return gap_plan
