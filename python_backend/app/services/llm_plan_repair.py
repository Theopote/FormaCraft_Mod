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


def build_minimal_plan_from_profile(
    profile: BuildingProfile,
    req: Optional[BuildRequest],
    user_text: str,
) -> Dict[str, Any]:
    """Deterministic compositional fallback when LLM output cannot validate."""
    sh = profile.scale_hints
    width = int(sh.typical_width_blocks or 32)
    depth = int(sh.typical_depth_blocks or max(16, width * 3 // 4))
    height = int(sh.typical_height_blocks or 16)
    width = max(8, min(width, 128))
    depth = max(8, min(depth, 128))
    height = max(6, min(height, 96))

    anchor = {"x": 0, "y": 64, "z": 0}
    if req is not None and getattr(req, "world", None) is not None:
        origin = getattr(req.world, "origin", None)
        if origin is not None:
            anchor = {
                "x": int(getattr(origin, "x", 0)),
                "y": int(getattr(origin, "y", 64)),
                "z": int(getattr(origin, "z", 0)),
            }

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
        "anchor": anchor,
        "global_constraints": {
            "facing": "SOUTH",
            "symmetry": "NONE",
            "terrain_strategy": "ADAPTIVE",
        },
        "layout": {
            "skeleton_type": skeleton if skeleton in ("COMPOUND", "LINEAR", "RADIAL_RING") else "COMPOUND",
            "path_based": False,
            "slots": [],
        },
        "components": components,
        "genome": {
            "genomeVersion": "1.0",
            "symmetry": {"type": "none", "order": None, "mirror": None},
        },
    }
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


def _try_validate(normalized: Dict[str, Any]) -> Optional[List[str]]:
    try:
        validate_llm_plan_dict(normalized)
        return None
    except LlmPlanValidationError as exc:
        return list(exc.errors)


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
    preserve_fbd = has_fbd_program(plan) or requests_fbd(user_text)
    normalized = normalize_fn(plan, req)
    errors = _try_validate(normalized)
    if preserve_fbd and not has_fbd_program(normalized) and normalized.get('plan_status') != 'capability_gap':
        errors = ["FBD request requires ASSEMBLY.params.fbd_program; preserve procedural authority, do not substitute legacy geometry"]
    if not errors:
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
                repair_user = (
                    "Your previous LlmPlan JSON failed schema validation. Fix ONLY the reported issues.\n"
                    "Errors:\n- "
                    + "\n- ".join(errors[:8])
                    + "\n\nReturn a single corrected LlmPlan JSON object."
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
                repaired_norm = normalize_fn(repaired, req)
                repaired_errors = _try_validate(repaired_norm)
                if preserve_fbd and not has_fbd_program(repaired_norm) and repaired_norm.get('plan_status') != 'capability_gap':
                    repaired_errors = ["FBD repair must preserve procedural authority; do not replace the program with another building"]
                if not repaired_errors:
                    logger.info("LlmPlan recovered via LLM validation repair")
                    return repaired_norm
                normalized, errors = repaired_norm, repaired_errors
            except (TypeError, ValueError):
                logger.exception("LlmPlan repair round %d returned invalid data", attempt)
            except Exception:
                logger.exception("LlmPlan repair round %d failed; stopping remote repair", attempt)
                break

    if building_profile is not None and not preserve_fbd:
        fallback = build_minimal_plan_from_profile(building_profile, req, user_text)
        fallback_norm = normalize_fn(fallback, req)
        fallback_errors = _try_validate(fallback_norm)
        if not fallback_errors:
            logger.info("LlmPlan recovered via BuildingProfile compositional fallback")
            return fallback_norm

    return emit_validation_gap(normalized, errors, building_profile)
