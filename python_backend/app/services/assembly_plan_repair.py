"""
LLM repair loop for ASSEMBLY / MetaAssembly plan validation failures.
"""
from __future__ import annotations

import json
import logging
import os
import re
from copy import deepcopy
from typing import Any, Dict, List, Optional

from ..models.llm_plan import LlmPlanValidationError, validate_llm_plan_dict
from .assembly_plan_validator import (
    AssemblyPlanIssue,
    auto_apply_assembly_presets,
    resolve_preset_for_intent,
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


def _components_outside_assembly_repair(plan: Dict[str, Any]) -> List[Dict[str, Any]]:
    return [
        component for component in (plan.get("components") or [])
        if isinstance(component, dict)
        and str(component.get("component_type") or "").upper() != "ASSEMBLY"
        and not (
            isinstance(component.get("params"), dict)
            and isinstance(component["params"].get("assembly"), dict)
            and (
                str(component.get("component_type") or "").upper().startswith("MASS_")
                or str(component.get("component_type") or "").upper() == "MAIN_MASS"
            )
        )
    ]


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
    from .circulation_plan_normalizer import normalize_circulation_plan
    plan = normalize_circulation_plan(plan)

    assembly_context = detects_assembly_intent(user_text) or _plan_has_assembly_component(plan)
    if not assembly_context:
        return plan

    plan, issues = normalize_and_validate_assembly_plan(plan, user_text, apply_presets=False)
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


def _surface_repair_scope(plan: Dict[str, Any], issues: List[AssemblyPlanIssue]) -> set[tuple[int, int]]:
    scope = set()
    for issue in issues:
        match = re.fullmatch(r"components\[(\d+)\]\.params\.assembly\.ops\[(\d+)\]\..+", issue.path)
        if not match:
            return set()
        component_index, op_index = map(int, match.groups())
        op = plan["components"][component_index]["params"]["assembly"]["ops"][op_index]
        if str(op.get("op", "")).upper() not in {"BEZIER_SURFACE", "REVOLVE_SURFACE", "LOFT_SURFACE"}:
            return set()
        scope.add((component_index, op_index))
    return scope


def _components_with_surface_repairs_masked(plan, scope):
    components = deepcopy(plan["components"])
    for component_index, op_index in scope:
        ops = components[component_index]["params"]["assembly"]["ops"]
        ops[op_index] = {"op": ops[op_index]["op"]}
    return components


def normalize_and_validate_assembly_plan(
    plan: Dict[str, Any],
    user_text: Optional[str],
    *,
    apply_presets: bool = True,
) -> tuple[Dict[str, Any], List[AssemblyPlanIssue]]:
    from .circulation_plan_normalizer import normalize_circulation_plan
    plan = normalize_circulation_plan(plan)
    if apply_presets:
        plan = auto_apply_assembly_presets(plan, user_text)
    requested_preset = resolve_preset_for_intent(user_text)
    allowed_presets = {requested_preset} if requested_preset is not None else set()
    issues = validate_assembly_plan(plan, allowed_presets=allowed_presets)
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
    surface_scope = _surface_repair_scope(plan, issues)
    if surface_scope:
        repair_user += "\nOnly edit the surface operations at the listed paths. Preserve all other operations, positions and components."
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
    normalized, _ = normalize_and_validate_assembly_plan(normalized, user_text)
    if str(normalized.get("plan_status") or "").strip().lower() != "capability_gap":
        if surface_scope:
            try:
                repaired_components = _components_with_surface_repairs_masked(normalized, surface_scope)
            except (IndexError, KeyError, TypeError) as exc:
                raise ValueError("Assembly repair removed a surface operation or changed its position") from exc
            if repaired_components != _components_with_surface_repairs_masked(plan, surface_scope):
                raise ValueError("Assembly repair changed components outside the diagnosed surface operations")
        if _plan_has_assembly_component(plan) and not _plan_has_assembly_component(normalized):
            raise ValueError("Assembly repair removed all Assembly components")
        if _components_outside_assembly_repair(normalized) != _components_outside_assembly_repair(plan):
            raise ValueError("Assembly repair changed components outside its repair scope")
        for field in ("mode", "anchor", "layout", "global_constraints", "style_profile", "style_attributes"):
            if normalized.get(field) != plan.get(field):
                raise ValueError(f"Assembly repair changed plan-wide field: {field}")
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
    if str(plan.get('plan_status') or '').lower() == 'capability_gap':
        return plan
    plan, issues = normalize_and_validate_assembly_plan(plan, user_text)
    from .fbd_capabilities import has_fbd_program
    if has_fbd_program(plan):
        # FBD uses its own typed repair contract.
        return finalize_assembly_plan_or_gap(plan, user_text)
    errors = [i for i in issues if i.severity == "ERROR"]
    if not errors:
        return plan
    if ASSEMBLY_REPAIR_MAX_RETRIES <= 0:
        logger.warning("Assembly plan has %d error(s) but repair disabled: %s", len(errors), errors[:3])
        return finalize_assembly_plan_or_gap(plan, user_text)

    current = plan
    for attempt in range(1, ASSEMBLY_REPAIR_MAX_RETRIES + 1):
        current, current_issues = normalize_and_validate_assembly_plan(current, user_text)
        current_issues = [i for i in current_issues if i.severity == "ERROR"]
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
