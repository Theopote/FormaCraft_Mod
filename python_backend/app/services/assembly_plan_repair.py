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
        return plan

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
    return current
