"""
LLM repair loop for ASSEMBLY / MetaAssembly plan validation failures.
"""
from __future__ import annotations

import json
import logging
import os
from typing import Any, Dict, List, Optional

from ..models.llm_plan import LlmPlanValidationError, validate_llm_plan_dict
from .assembly_plan_validator import (
    AssemblyPlanIssue,
    auto_apply_assembly_presets,
    format_repair_prompt,
    validate_assembly_plan,
)

logger = logging.getLogger(__name__)

ASSEMBLY_REPAIR_MAX_RETRIES = int(os.getenv("ASSEMBLY_REPAIR_MAX_RETRIES", "2"))


def build_capability_gap(
    issues: List[AssemblyPlanIssue],
    *,
    fallback_message: str = "Assembly plan could not be validated or repaired.",
) -> Dict[str, Any]:
    errors = [i for i in issues if i.severity == "ERROR"]
    first = errors[0] if errors else AssemblyPlanIssue("plan", "E_ASSEMBLY_VALIDATION", fallback_message)
    suggestions: List[str] = []
    for issue in errors[:4]:
        suggestions.append(f"{issue.code}: {issue.message}")
    suggestions.extend([
        "Prefer preset: spiral_watchtower | suspension_bridge_simple | gothic_shell_box.",
        "Use exported graph component types and port ids only.",
        "If geometry is unsupported, return plan_status=capability_gap explicitly.",
    ])
    return {
        "code": first.code,
        "message": first.message,
        "path": first.path,
        "suggestions": suggestions[:6],
    }


def _plan_has_assembly_component(plan: Dict[str, Any]) -> bool:
    comps = plan.get("components")
    if not isinstance(comps, list):
        return False
    return any(
        isinstance(c, dict) and str(c.get("component_type") or "").upper() == "ASSEMBLY"
        for c in comps
    )


def finalize_assembly_plan_or_gap(
    plan: Dict[str, Any],
    user_text: Optional[str],
) -> Dict[str, Any]:
    """
    After validation/repair, convert unresolved ASSEMBLY errors into explicit capability_gap.
    Java preview treats plan_status=capability_gap as a handled failure (no silent HOUSE fallback).
    """
    if not isinstance(plan, dict):
        return plan
    if str(plan.get("plan_status") or "").strip().lower() == "capability_gap":
        return plan

    from .assembly_plan_validator import detects_assembly_intent

    assembly_context = detects_assembly_intent(user_text) or _plan_has_assembly_component(plan)
    if not assembly_context:
        return plan

    _, issues = normalize_and_validate_assembly_plan(plan, user_text, apply_presets=False)
    errors = [i for i in issues if i.severity == "ERROR"]
    if not errors:
        return plan

    gap = build_capability_gap(errors)
    out = dict(plan)
    out["plan_status"] = "capability_gap"
    out["error"] = gap["message"]
    out["capability_gap"] = gap
    logger.warning(
        "Assembly plan unresolved after repair; emitting capability_gap %s at %s",
        gap.get("code"),
        gap.get("path"),
    )
    return out


def normalize_and_validate_assembly_plan(
    plan: Dict[str, Any],
    user_text: Optional[str],
    *,
    apply_presets: bool = True,
) -> tuple[Dict[str, Any], List[AssemblyPlanIssue]]:
    if apply_presets:
        plan = auto_apply_assembly_presets(plan, user_text)
    issues = validate_assembly_plan(plan)
    return plan, issues


def repair_assembly_plan_with_llm(
    client: Any,
    model: str,
    messages: List[Dict[str, str]],
    plan: Dict[str, Any],
    issues: List[AssemblyPlanIssue],
    *,
    call_with_timeout: Any,
    timeout_sec: float,
    prefer_json_object: bool,
    normalize_fn: Any,
    req: Any,
) -> Dict[str, Any]:
    """One repair attempt: append assistant plan + user repair instructions, re-call LLM."""
    from .ai_planner import call_chat_with_llm_plan_response_formats

    repair_user = format_repair_prompt(plan, issues)
    repair_messages = list(messages)
    repair_messages.append({"role": "assistant", "content": json.dumps(plan, ensure_ascii=False)})
    repair_messages.append({"role": "user", "content": repair_user})

    response, _fmt = call_chat_with_llm_plan_response_formats(
        client,
        model=model,
        messages=repair_messages,
        temperature=0.3,
        call_with_timeout=call_with_timeout,
        timeout_sec=timeout_sec,
        prefer_json_object=prefer_json_object,
    )
    raw = response.choices[0].message.content
    if not raw or not str(raw).strip():
        raise ValueError("Empty LLM response during assembly repair")
    try:
        repaired = json.loads(raw)
    except json.JSONDecodeError as e:
        raise ValueError(f"Assembly repair returned invalid JSON: {e}") from e
    normalized = normalize_fn(repaired, req)
    validate_llm_plan_dict(normalized)
    user_text = getattr(req, "userMessage", None) if req is not None else None
    normalized, post_issues = normalize_and_validate_assembly_plan(normalized, user_text)
    return normalized


def maybe_repair_assembly_plan(
    client: Any,
    model: str,
    messages: List[Dict[str, str]],
    plan: Dict[str, Any],
    user_text: Optional[str],
    *,
    call_with_timeout: Any,
    timeout_sec: float,
    prefer_json_object: bool,
    normalize_fn: Any,
    req: Any,
) -> Dict[str, Any]:
    plan, issues = normalize_and_validate_assembly_plan(plan, user_text)
    errors = [i for i in issues if i.severity == "ERROR"]
    if not errors:
        return plan
    if ASSEMBLY_REPAIR_MAX_RETRIES <= 0:
        logger.warning("Assembly plan has %d error(s) but repair disabled: %s", len(errors), errors[:3])
        return finalize_assembly_plan_or_gap(plan, user_text)

    current = plan
    for attempt in range(1, ASSEMBLY_REPAIR_MAX_RETRIES + 1):
        current_issues = [i for i in validate_assembly_plan(current) if i.severity == "ERROR"]
        if not current_issues:
            break
        logger.warning(
            "Assembly plan validation failed (attempt %d/%d), requesting LLM repair: %s",
            attempt,
            ASSEMBLY_REPAIR_MAX_RETRIES,
            current_issues[:3],
        )
        try:
            current = repair_assembly_plan_with_llm(
                client,
                model,
                messages,
                current,
                current_issues,
                call_with_timeout=call_with_timeout,
                timeout_sec=timeout_sec,
                prefer_json_object=prefer_json_object,
                normalize_fn=normalize_fn,
                req=req,
            )
        except (ValueError, LlmPlanValidationError, json.JSONDecodeError) as e:
            logger.warning("Assembly repair attempt %d failed: %s", attempt, e)
            break
    return finalize_assembly_plan_or_gap(current, user_text)
