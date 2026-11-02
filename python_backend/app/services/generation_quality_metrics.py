"""Layered plan_valid / geometry / world_apply / intent_fidelity — never treat HTTP 200 as semantic success."""

from __future__ import annotations

import logging
import threading
from typing import Any, Dict, List, Optional

_LOG = logging.getLogger("formacraft.generation_quality")
_LOCK = threading.Lock()


class GenerationQualityMetrics:
    plan_valid_success = 0
    plan_valid_fail = 0
    geometry_success = 0
    geometry_fail = 0
    world_apply_success = 0
    world_apply_fail = 0
    intent_fidelity_success = 0
    intent_fidelity_fail = 0
    autofill_events = 0

    @classmethod
    def reset_for_tests(cls) -> None:
        with _LOCK:
            cls.plan_valid_success = 0
            cls.plan_valid_fail = 0
            cls.geometry_success = 0
            cls.geometry_fail = 0
            cls.world_apply_success = 0
            cls.world_apply_fail = 0
            cls.intent_fidelity_success = 0
            cls.intent_fidelity_fail = 0
            cls.autofill_events = 0

    @classmethod
    def snapshot(cls) -> Dict[str, int]:
        with _LOCK:
            return {
                "plan_valid_success": cls.plan_valid_success,
                "plan_valid_fail": cls.plan_valid_fail,
                "geometry_success": cls.geometry_success,
                "geometry_fail": cls.geometry_fail,
                "world_apply_success": cls.world_apply_success,
                "world_apply_fail": cls.world_apply_fail,
                "intent_fidelity_success": cls.intent_fidelity_success,
                "intent_fidelity_fail": cls.intent_fidelity_fail,
                "autofill_events": cls.autofill_events,
                # legacy keys (parse≈plan_valid, build≈geometry)
                "parse_success": cls.plan_valid_success,
                "parse_fail": cls.plan_valid_fail,
                "build_success": cls.geometry_success,
                "build_fail": cls.geometry_fail,
            }


def record_parse_outcome(
    ok: bool,
    *,
    request_id: str = "",
    user_text: str = "",
    reason: str = "",
) -> None:
    with _LOCK:
        if ok:
            GenerationQualityMetrics.plan_valid_success += 1
        else:
            GenerationQualityMetrics.plan_valid_fail += 1
    _LOG.info(
        "[GenQuality] plan_valid=%s requestId=%s reason=%s text=%r metrics=%s",
        ok,
        request_id,
        reason or ("ok" if ok else "parse_failed"),
        (user_text or "")[:80],
        GenerationQualityMetrics.snapshot(),
    )


def record_plan_outcome(
    *,
    parse_ok: bool,
    build_ok: bool,
    intent_fidelity_ok: bool,
    request_id: str = "",
    user_text: str = "",
    intent_class: str = "",
    style_profile: str = "",
    reason: str = "",
    plan_summary: str = "",
    defaults_reason: str = "",
    geometry_ok: Optional[bool] = None,
    world_apply_ok: Optional[bool] = None,
) -> None:
    """Record layered outcomes. Placement/HTTP success must not imply intent_fidelity_ok."""
    geom = build_ok if geometry_ok is None else geometry_ok
    world = build_ok if world_apply_ok is None else world_apply_ok
    with _LOCK:
        if parse_ok:
            GenerationQualityMetrics.plan_valid_success += 1
        else:
            GenerationQualityMetrics.plan_valid_fail += 1
        if geom:
            GenerationQualityMetrics.geometry_success += 1
        else:
            GenerationQualityMetrics.geometry_fail += 1
        if world:
            GenerationQualityMetrics.world_apply_success += 1
        else:
            GenerationQualityMetrics.world_apply_fail += 1
        if intent_fidelity_ok:
            GenerationQualityMetrics.intent_fidelity_success += 1
        else:
            GenerationQualityMetrics.intent_fidelity_fail += 1
    _LOG.info(
        "[GenQuality] requestId=%s plan_valid=%s geometry=%s world_apply=%s intent_fidelity=%s "
        "intent=%s style=%s reason=%s defaults=%s plan=%s text=%r metrics=%s",
        request_id,
        parse_ok,
        geom,
        world,
        intent_fidelity_ok,
        intent_class,
        style_profile,
        reason,
        defaults_reason,
        plan_summary,
        (user_text or "")[:80],
        GenerationQualityMetrics.snapshot(),
    )


def record_autofill_diagnostics(
    *,
    request_id: str = "",
    autofills: Optional[List[str]] = None,
    roof_type: Optional[str] = None,
    materials: Optional[Dict[str, Any]] = None,
) -> None:
    with _LOCK:
        GenerationQualityMetrics.autofill_events += 1
    _LOG.info(
        "[GenQuality] autofill requestId=%s items=%s roof=%s materials=%s",
        request_id,
        autofills or [],
        roof_type,
        materials or {},
    )


def summarize_plan(plan: Optional[Dict[str, Any]]) -> str:
    if not isinstance(plan, dict):
        return "plan=null"
    comps = plan.get("components") if isinstance(plan.get("components"), list) else []
    types: List[str] = []
    for c in comps:
        if isinstance(c, dict):
            t = str(c.get("component_type") or c.get("componentType") or "")
            if t:
                types.append(t)
    return (
        f"status={plan.get('plan_status') or 'ok'} "
        f"components={len(comps)} types={','.join(types[:8])} "
        f"style={plan.get('style_profile') or ''}"
    )
