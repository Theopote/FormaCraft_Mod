"""Split parse / build / intent_fidelity success — never treat HTTP 200 as semantic success."""

from __future__ import annotations

import logging
import threading
from typing import Any, Dict, List, Optional

_LOG = logging.getLogger("formacraft.generation_quality")
_LOCK = threading.Lock()


class GenerationQualityMetrics:
    parse_success = 0
    parse_fail = 0
    build_success = 0
    build_fail = 0
    intent_fidelity_success = 0
    intent_fidelity_fail = 0
    autofill_events = 0

    @classmethod
    def reset_for_tests(cls) -> None:
        with _LOCK:
            cls.parse_success = 0
            cls.parse_fail = 0
            cls.build_success = 0
            cls.build_fail = 0
            cls.intent_fidelity_success = 0
            cls.intent_fidelity_fail = 0
            cls.autofill_events = 0

    @classmethod
    def snapshot(cls) -> Dict[str, int]:
        with _LOCK:
            return {
                "parse_success": cls.parse_success,
                "parse_fail": cls.parse_fail,
                "build_success": cls.build_success,
                "build_fail": cls.build_fail,
                "intent_fidelity_success": cls.intent_fidelity_success,
                "intent_fidelity_fail": cls.intent_fidelity_fail,
                "autofill_events": cls.autofill_events,
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
            GenerationQualityMetrics.parse_success += 1
        else:
            GenerationQualityMetrics.parse_fail += 1
    _LOG.info(
        "[GenQuality] parse_success=%s requestId=%s reason=%s text=%r metrics=%s",
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
) -> None:
    """Record layered outcomes. Placement/HTTP success must not imply intent_fidelity_ok."""
    with _LOCK:
        if parse_ok:
            GenerationQualityMetrics.parse_success += 1
        else:
            GenerationQualityMetrics.parse_fail += 1
        if build_ok:
            GenerationQualityMetrics.build_success += 1
        else:
            GenerationQualityMetrics.build_fail += 1
        if intent_fidelity_ok:
            GenerationQualityMetrics.intent_fidelity_success += 1
        else:
            GenerationQualityMetrics.intent_fidelity_fail += 1
    _LOG.info(
        "[GenQuality] requestId=%s parse=%s build=%s intent_fidelity=%s "
        "intent=%s style=%s reason=%s defaults=%s plan=%s text=%r metrics=%s",
        request_id,
        parse_ok,
        build_ok,
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
