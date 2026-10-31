"""Backend-owned prompt assembly for LlmPlan requests.

Clients should send structured fields (userMessage / userPrompt / systemPrompt)
rather than a client-assembled blob split on the fragile \"USER REQUEST:\" marker.
"""

from __future__ import annotations

from typing import Optional, Tuple

from ..models.request import BuildRequest


def resolve_llm_plan_prompts(
    req: BuildRequest,
    *,
    default_system: str,
) -> Tuple[str, str]:
    """
    Returns (system_prompt, user_prompt).

    Priority:
    1. Explicit systemPrompt / userPrompt fields
    2. userMessage (+ backend default system)
    3. Legacy requestText with \"USER REQUEST:\" split (compat only)
    4. requestText as user with backend default system
    """
    explicit_system = (getattr(req, "systemPrompt", None) or "").strip()
    explicit_user = (getattr(req, "userPrompt", None) or "").strip()
    if explicit_system or explicit_user:
        user = explicit_user or (req.userMessage or "").strip() or (req.requestText or "").strip()
        system = explicit_system or default_system
        return system, user

    user_message = (req.userMessage or "").strip()
    if user_message:
        # Preferred path: client sends raw user text; backend owns the system prompt.
        return default_system, user_message

    request_text = req.requestText or ""
    # Legacy marker split — only when userMessage is absent (old clients).
    if "USER REQUEST:" in request_text:
        parts = request_text.split("USER REQUEST:", 1)
        system_prompt = parts[0].strip() or default_system
        user_prompt = parts[1].strip() if len(parts) > 1 else ""
        return system_prompt, user_prompt

    return default_system, request_text.strip()


def research_user_text(req: BuildRequest, user_prompt: str) -> str:
    text = (req.userMessage or "").strip()
    if text:
        return text
    return (user_prompt or "").strip()
