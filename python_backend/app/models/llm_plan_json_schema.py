"""
OpenAI-style JSON Schema for LlmPlan response_format.

Used by generate_llm_plan() (with json_object fallback) and probe_json_schema_support.py.
"""
from __future__ import annotations

import logging
import os
from typing import Any, Dict, List, Tuple

logger = logging.getLogger(__name__)

try:
    from openai import BadRequestError as OpenAIBadRequestError
except Exception:  # pragma: no cover - optional dependency shape
    OpenAIBadRequestError = None  # type: ignore[misc, assignment]


def _vec3i_schema() -> Dict[str, Any]:
    return {
        "type": "object",
        "properties": {
            "x": {"type": "integer"},
            "y": {"type": "integer"},
            "z": {"type": "integer"},
        },
        "required": ["x", "y", "z"],
        "additionalProperties": False,
    }


def _dimensions_schema() -> Dict[str, Any]:
    return {
        "type": "object",
        "properties": {
            "width": {"type": "integer"},
            "depth": {"type": "integer"},
            "height": {"type": "integer"},
        },
        "required": ["width", "depth", "height"],
        "additionalProperties": False,
    }


def _feature_item_schema(*, strict: bool) -> Dict[str, Any]:
    if strict:
        return {
            "type": "object",
            "properties": {
                "component_request": {
                    "type": "object",
                    "properties": {},
                    "additionalProperties": False,
                },
                "group_request": {
                    "type": "object",
                    "properties": {},
                    "additionalProperties": False,
                },
            },
            "required": ["component_request", "group_request"],
            "additionalProperties": False,
        }
    return {
        "anyOf": [
            {"type": "string"},
            {
                "type": "object",
                "properties": {
                    "component_request": {"type": "object", "additionalProperties": True},
                    "group_request": {"type": "object", "additionalProperties": True},
                },
                "additionalProperties": True,
            },
        ]
    }


def _component_schema(*, strict: bool) -> Dict[str, Any]:
    if strict:
        return {
            "type": "object",
            "properties": {
                "component_type": {"type": "string"},
                "slot_id": {"type": ["string", "null"]},
                "relative_position": _vec3i_schema(),
                "dimensions": _dimensions_schema(),
                "features": {
                    "type": "array",
                    "items": {"type": "string"},
                },
                "params": {
                    "type": "object",
                    "properties": {},
                    "additionalProperties": False,
                },
            },
            "required": [
                "component_type",
                "slot_id",
                "relative_position",
                "dimensions",
                "features",
                "params",
            ],
            "additionalProperties": False,
        }
    return {
        "type": "object",
        "properties": {
            "component_type": {"type": "string"},
            "slot_id": {"type": "string"},
            "relative_position": _vec3i_schema(),
            "dimensions": _dimensions_schema(),
            "features": {
                "type": "array",
                "items": _feature_item_schema(strict=False),
            },
            "params": {"type": "object", "additionalProperties": True},
        },
        "required": ["component_type", "relative_position", "dimensions"],
        "additionalProperties": True,
    }


def build_llm_plan_json_schema(*, strict: bool = True) -> Dict[str, Any]:
    """
    JSON Schema for LlmPlan.

    strict=True: minimal strict-mode-compatible core (mode, anchor, components).
    strict=False: broader schema for providers that support loose json_schema.
    """
    if strict:
        return {
            "type": "object",
            "properties": {
                "mode": {"type": "string", "enum": ["build", "patch"]},
                "style_profile": {"type": ["string", "null"]},
                "anchor": _vec3i_schema(),
                "components": {
                    "type": ["array", "null"],
                    "items": _component_schema(strict=True),
                },
                "global_constraints": {
                    "type": ["object", "null"],
                    "properties": {},
                    "additionalProperties": False,
                },
                "layout": {
                    "type": ["object", "null"],
                    "properties": {},
                    "additionalProperties": False,
                },
                "genome": {
                    "type": ["object", "null"],
                    "properties": {},
                    "additionalProperties": False,
                },
                "style_attributes": {
                    "type": ["object", "null"],
                    "properties": {},
                    "additionalProperties": False,
                },
                "patch": {
                    "type": ["object", "null"],
                    "properties": {},
                    "additionalProperties": False,
                },
            },
            "required": [
                "mode",
                "style_profile",
                "anchor",
                "components",
                "global_constraints",
                "layout",
                "genome",
                "style_attributes",
                "patch",
            ],
            "additionalProperties": False,
        }

    return {
        "type": "object",
        "properties": {
            "mode": {"type": "string", "enum": ["build", "patch"]},
            "style_profile": {"type": "string"},
            "anchor": _vec3i_schema(),
            "global_constraints": {"type": "object", "additionalProperties": True},
            "layout": {"type": "object", "additionalProperties": True},
            "components": {
                "type": "array",
                "items": _component_schema(strict=False),
            },
            "genome": {"type": "object", "additionalProperties": True},
            "style_attributes": {"type": "object", "additionalProperties": True},
            "target_slot_id": {"type": "string"},
            "allowed_area": {"type": "string"},
            "patch": {"type": "object", "additionalProperties": True},
            "plan_program": {"type": "object", "additionalProperties": True},
            "plan_skeleton": {"type": "object", "additionalProperties": True},
        },
        "required": ["mode", "anchor"],
        "additionalProperties": True,
    }


def build_llm_plan_response_format(*, strict: bool = True) -> Dict[str, Any]:
    return {
        "type": "json_schema",
        "json_schema": {
            "name": "llm_plan",
            "strict": strict,
            "schema": build_llm_plan_json_schema(strict=strict),
        },
    }


JSON_OBJECT_RESPONSE_FORMAT: Dict[str, Any] = {"type": "json_object"}


def resolve_llmplan_json_schema_mode() -> str:
    """auto | on | off — whether to attempt json_schema before json_object."""
    return (os.getenv("LLMPLAN_JSON_SCHEMA") or "auto").strip().lower()


def resolve_llmplan_json_schema_strict() -> bool:
    """Use strict json_schema when true; loose schema when false."""
    value = (os.getenv("LLMPLAN_JSON_SCHEMA_STRICT") or "true").strip().lower()
    return value not in ("0", "false", "no", "off")


def iter_llm_plan_response_formats() -> List[Tuple[str, Dict[str, Any]]]:
    """
    Ordered response_format candidates for generate_llm_plan().

    - off: json_object only
    - on:  json_schema only (no fallback)
    - auto (default): json_schema then json_object fallback
    """
    mode = resolve_llmplan_json_schema_mode()
    if mode in ("off", "false", "0", "no"):
        return [("json_object", JSON_OBJECT_RESPONSE_FORMAT)]

    strict = resolve_llmplan_json_schema_strict()
    schema_entry = ("json_schema", build_llm_plan_response_format(strict=strict))
    if mode in ("on", "true", "1", "yes"):
        return [schema_entry]
    return [schema_entry, ("json_object", JSON_OBJECT_RESPONSE_FORMAT)]


def should_fallback_to_json_object(exc: BaseException) -> bool:
    """Retry with json_object only for provider/schema rejection, not auth/timeout."""
    if isinstance(exc, TimeoutError):
        return False
    if OpenAIBadRequestError is not None and isinstance(exc, OpenAIBadRequestError):
        return True

    msg = str(exc).lower()
    if any(token in msg for token in ("timed out", "timeout", "unauthorized", "authentication", "api key")):
        return False
    if "rate limit" in msg or "rate_limit" in msg:
        return False

    schema_markers = (
        "json_schema",
        "response_format",
        "structured output",
        "structured outputs",
        "invalid schema",
        "not supported",
        "unknown parameter",
        "unsupported",
    )
    if any(marker in msg for marker in schema_markers):
        return True

    # Unknown API errors during json_schema attempt: still try json_object once.
    return True


def call_chat_with_llm_plan_response_formats(
    client: Any,
    *,
    model: str,
    messages: List[Dict[str, Any]],
    temperature: float,
    call_with_timeout: Any,
    timeout_sec: float,
) -> Tuple[Any, str]:
    """
    Call chat.completions.create with json_schema first (if enabled), then json_object.

    Returns (response, format_label).
    """
    formats = iter_llm_plan_response_formats()
    last_exc: BaseException | None = None

    for index, (label, response_format) in enumerate(formats):
        is_schema_attempt = label == "json_schema"
        try:
            response = call_with_timeout(
                lambda rf=response_format: client.chat.completions.create(
                    model=model,
                    messages=messages,
                    response_format=rf,
                    temperature=temperature,
                ),
                timeout_sec,
            )
        except Exception as exc:
            last_exc = exc
            has_fallback = index + 1 < len(formats)
            if is_schema_attempt and has_fallback and should_fallback_to_json_object(exc):
                logger.warning(
                    "LlmPlan: json_schema rejected by provider (%s: %s); retrying json_object",
                    type(exc).__name__,
                    exc,
                )
                continue
            raise

        if is_schema_attempt:
            logger.info(
                "LlmPlan: response_format=json_schema strict=%s",
                resolve_llmplan_json_schema_strict(),
            )
        elif len(formats) > 1:
            logger.warning("LlmPlan: using json_object fallback response_format")
        return response, label

    if last_exc is not None:
        raise last_exc
    raise RuntimeError("LlmPlan: no response_format candidates configured")
