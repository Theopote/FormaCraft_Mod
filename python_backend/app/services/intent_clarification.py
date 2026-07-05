"""
Assess whether a build request needs clarifying questions before planning.

Supports multi-turn: chatHistory + userMessage are merged for classification;
follow-up answers skip re-asking when the user already responded to a prior prompt.
"""

from __future__ import annotations

import os
import re
from typing import Any, List, Optional

from ..models.intent_clarification import ClarificationAssessment

_VAGUE_ONLY_RE = re.compile(
    r"^(?:"
    r"(?:建|造|盖|生成|做一个?|帮我建|帮我造|帮我生成)\s*"
    r"(?:一个?|一座|一栋)?\s*"
    r"(?:建筑|房子|房|大楼|楼|东西|building)?\s*"
    r"|"
    r"(?:build|make|create)\s+(?:a\s+)?(?:building|house|something|it)?\s*"
    r")$",
    re.IGNORECASE,
)

_STYLE_OR_SCALE_HINTS = (
    "哥特", "现代", "中式", "古典", "巴洛克", "赛博", "工业", "童话",
    "gothic", "modern", "baroque", "style", "风格",
    "层", "楼高", "米高", "m ", "meter", "floor", "宽", "高", "深",
    "×", "x", "格", "block",
)

_CLARIFICATION_AI_MARKERS = (
    "需要再确认", "需要补充", "请问", "能否说明", "clarification",
    "哪一类建筑", "具体建筑", "参考图",
)


def is_intent_clarification_enabled() -> bool:
    raw = (os.getenv("INTENT_CLARIFICATION") or "on").strip().lower()
    return raw not in ("off", "0", "false", "no")


def build_effective_intent_text(req: Any) -> str:
    """Merge recent chat + current user message for multi-turn understanding."""
    parts: List[str] = []
    history = getattr(req, "chatHistory", None) or []
    for line in history[-8:]:
        s = str(line or "").strip()
        if not s:
            continue
        if s.lower().startswith("player:"):
            parts.append(s[7:].strip())
        elif s.lower().startswith("ai:"):
            parts.append(s[3:].strip())
        else:
            parts.append(s)
    user = (getattr(req, "userMessage", None) or "").strip()
    if user:
        parts.append(user)
    return "\n".join(parts).strip()


def is_follow_up_to_clarification(req: Any) -> bool:
    history = getattr(req, "chatHistory", None) or []
    for line in history[-4:]:
        lower = str(line or "").lower()
        if not lower.startswith("ai:"):
            continue
        if any(m in lower for m in _CLARIFICATION_AI_MARKERS):
            return True
    return False


def _has_style_or_scale_hint(text: str) -> bool:
    lower = (text or "").lower()
    return any(h in lower or h in (text or "") for h in _STYLE_OR_SCALE_HINTS)


def _is_patch_or_edit_mode(req: Any) -> bool:
    mode = (getattr(req, "promptMode", None) or "").strip().upper()
    return mode in ("PATCH", "MODIFY_REGION")


def _build_questions(reason: str, user_text: str) -> List[str]:
    if reason == "empty_or_too_short":
        return [
            "你想建造什么？可以是具体地标（如圣家族大教堂）、建筑类型（哥特教堂）或风格描述。",
            "有目标尺寸吗？（例如宽30×深20×高15格）",
        ]
    if reason == "vague_intent":
        return [
            "你想做的是哪一类建筑？（住宅、教堂、城堡、体育馆、博物馆等）",
            "是要复刻某座真实地标，还是只要某种风格的原创建筑？",
            "大概尺寸或层数有要求吗？",
        ]
    if reason == "generic_typology":
        return [
            "你说的建筑类型较宽泛——想要哪种风格？（例如哥特、现代、中式、童话城堡）",
            "有具体建筑名称或参考图吗？有的话能大幅提高还原度。",
            "期望的大致尺寸？（宽×深×高，或层数）",
        ]
    if reason == "low_confidence":
        return [
            "能否补充建筑名称、风格或用途？（例如「苏州博物馆风格的小型展馆」）",
            "是要真实地标复刻，还是风格化原创？",
        ]
    if reason == "no_build_intent":
        return [
            "请描述想生成的建筑：名称、风格、尺寸中的至少一项。",
            "若有参考链接或图片，也可以一并提供。",
        ]
    return [
        "请补充建筑类型、风格或具体名称中的至少一项。",
        "有参考图/链接的话也可以附上。",
    ]


def format_clarification_message(questions: List[str]) -> str:
    lines = ["需要再确认几件事，这样生成会更靠谱：", ""]
    for i, q in enumerate(questions[:3], 1):
        lines.append(f"{i}. {q}")
    lines.append("")
    lines.append("请直接回复补充信息（可合并成一条消息）；我会结合上文继续理解。")
    return "\n".join(lines)


def assess_clarification_needs(req: Any) -> ClarificationAssessment:
    """Return whether we should ask the user before running LlmPlan."""
    if not is_intent_clarification_enabled():
        return ClarificationAssessment(needs_clarification=False)

    if _is_patch_or_edit_mode(req):
        return ClarificationAssessment(needs_clarification=False)

    refs = getattr(req, "references", None) or []
    if refs:
        return ClarificationAssessment(needs_clarification=False)

    user_text = (getattr(req, "userMessage", None) or "").strip()
    effective = build_effective_intent_text(req)

    if is_follow_up_to_clarification(req):
        if len(effective) >= 6:
            return ClarificationAssessment(needs_clarification=False)

    if not user_text and not effective:
        qs = _build_questions("empty_or_too_short", "")
        return ClarificationAssessment(
            needs_clarification=True,
            reason="empty_or_too_short",
            questions_zh=qs,
            message_zh=format_clarification_message(qs),
            confidence=0.95,
        )

    if len(user_text) < 3 and len(effective) < 8:
        qs = _build_questions("empty_or_too_short", user_text)
        return ClarificationAssessment(
            needs_clarification=True,
            reason="empty_or_too_short",
            questions_zh=qs,
            message_zh=format_clarification_message(qs),
            confidence=0.9,
        )

    from .building_research_agent import _has_build_intent, _is_edit_or_patch_prompt

    if _is_edit_or_patch_prompt(user_text):
        return ClarificationAssessment(needs_clarification=False)

    classify_text = effective or user_text

    if _VAGUE_ONLY_RE.match(user_text) or _VAGUE_ONLY_RE.match(classify_text):
        qs = _build_questions("vague_intent", user_text)
        return ClarificationAssessment(
            needs_clarification=True,
            reason="vague_intent",
            questions_zh=qs,
            message_zh=format_clarification_message(qs),
            confidence=0.88,
        )

    if not _has_build_intent(classify_text) and len(classify_text) < 20:
        qs = _build_questions("no_build_intent", user_text)
        return ClarificationAssessment(
            needs_clarification=True,
            reason="no_build_intent",
            questions_zh=qs,
            message_zh=format_clarification_message(qs),
            confidence=0.82,
        )

    from .building_request_classifier import classify_building_request_local

    classification = classify_building_request_local(classify_text)

    if (
        not classification.is_specific_real_building
        and classification.confidence >= 0.55
        and not _has_style_or_scale_hint(classify_text)
    ):
        qs = _build_questions("generic_typology", user_text)
        return ClarificationAssessment(
            needs_clarification=True,
            reason="generic_typology",
            questions_zh=qs,
            message_zh=format_clarification_message(qs),
            confidence=classification.confidence,
        )

    if (
        not classification.is_specific_real_building
        and classification.confidence < 0.5
        and len(classify_text) < 24
    ):
        qs = _build_questions("low_confidence", user_text)
        return ClarificationAssessment(
            needs_clarification=True,
            reason="low_confidence",
            questions_zh=qs,
            message_zh=format_clarification_message(qs),
            confidence=classification.confidence,
        )

    return ClarificationAssessment(needs_clarification=False)


def maybe_clarification_response(req: Any) -> Optional[dict]:
    """If clarification is needed, return a /build response dict; else None."""
    assessment = assess_clarification_needs(req)
    if not assessment.needs_clarification:
        return None
    payload = {
        "needs_clarification": True,
        "reason": assessment.reason,
        "questions": assessment.questions_zh,
        "message_zh": assessment.message_zh,
        "session_id": getattr(req, "sessionId", None),
    }
    return {"kind": "clarification", "clarification": payload}
