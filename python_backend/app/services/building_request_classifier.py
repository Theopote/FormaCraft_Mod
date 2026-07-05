"""
Stage-1 request classification — specific real building vs generic typology.

Decouples "should we research / treat as a named landmark?" from archetype MODULE routing.
Keyword tables cannot distinguish「大教堂」vs「圣家族大教堂」; LLM + remainder heuristics can.
"""

from __future__ import annotations

import json
import logging
import os
import re
from typing import Any, Callable, Optional

from ..models.building_profile import RequestClassification

logger = logging.getLogger(__name__)

_CLASSIFICATION_MIN_CONFIDENCE = 0.55

_GENERIC_STYLE_RE = re.compile(
    r"(?:哥特|巴洛克|现代|中世纪|罗马|拜占庭|装饰艺术|解构|新古典|"
    r"gothic|baroque|modern|medieval|romanesque|byzantine|art\s*deco|deconstruct)",
    re.IGNORECASE,
)

_GENERIC_TYPology_RE = re.compile(
    r"(?:"
    r"(?:一个|一座|一栋|一间|帮我|请)?\s*"
    r"(?:哥特|现代|中世纪|罗马)?(?:风格)?(?:的)?\s*"
    r"(?:大教堂|教堂|城堡|寺庙|佛塔|体育馆|博物馆|大楼|别墅|宫殿|神庙|塔楼)"
    r"|"
    r"(?:a|an)\s+(?:gothic|modern|medieval|romanesque)?\s*(?:style\s+)?"
    r"(?:cathedral|church|castle|temple|pagoda|stadium|museum|tower|palace|villa)"
    r")",
    re.IGNORECASE,
)

_BROAD_TYPE_WORDS = frozenset({
    "大教堂", "教堂", "城堡", "寺庙", "佛塔", "体育馆", "博物馆", "大楼", "别墅",
    "宫殿", "神庙", "塔楼", "cathedral", "church", "castle", "temple", "pagoda",
    "stadium", "museum", "tower", "palace", "villa", "fortress", "shrine",
})


def is_request_classification_enabled() -> bool:
    raw = (os.getenv("BUILDING_REQUEST_CLASSIFICATION") or "on").strip().lower()
    return raw not in ("off", "0", "false", "no")


def is_request_classification_llm_enabled() -> bool:
    raw = (os.getenv("BUILDING_REQUEST_CLASSIFICATION_LLM") or "on").strip().lower()
    return raw not in ("off", "0", "false", "no")


def _classification_timeout_sec() -> float:
    try:
        return float(os.getenv("BUILDING_REQUEST_CLASSIFICATION_TIMEOUT_SEC", "5"))
    except (TypeError, ValueError):
        return 5.0


def _subject_is_broad_type_only(subject: str) -> bool:
    s = (subject or "").strip().lower()
    if not s:
        return True
    return s in {w.lower() for w in _BROAD_TYPE_WORDS}


def classify_building_request_local(user_text: str) -> RequestClassification:
    """Fast rule-based classification (zero LLM latency)."""
    from .building_research_agent import _extract_subject, _is_edit_or_patch_prompt

    text = (user_text or "").strip()
    if not text:
        return RequestClassification(
            is_specific_real_building=False,
            confidence=0.9,
            reasoning_hint="empty request",
            source="rules",
        )

    from .landmark_alias_matcher import has_specific_remainder, is_broad_alias

    if _is_edit_or_patch_prompt(text):
        return RequestClassification(
            is_specific_real_building=False,
            confidence=0.85,
            reasoning_hint="patch/edit prompt, not a new building identity",
            source="rules",
        )

    lower = text.lower()
    subject = _extract_subject(text) or ""

    # Proper-noun archetype hit → specific (before generic-regex checks)
    try:
        from .archetype_detector import detect_archetype_local

        match = detect_archetype_local(text)
        if match and match.qualifies_for_module_route():
            return RequestClassification(
                is_specific_real_building=True,
                building_name_normalized=subject or match.matched_alias or match.id,
                confidence=match.confidence,
                reasoning_hint="explicit preset landmark alias match",
                source="rules",
            )
        if match and "generic_typology" in match.reason_tags:
            return RequestClassification(
                is_specific_real_building=False,
                confidence=match.confidence,
                reasoning_hint="generic typology alias only",
                source="rules",
            )
    except Exception:
        pass

    # Explicit generic typology phrases
    if _GENERIC_TYPology_RE.search(text):
        if not subject or _subject_is_broad_type_only(subject):
            return RequestClassification(
                is_specific_real_building=False,
                confidence=0.8,
                reasoning_hint="generic building typology without a unique proper name",
                source="rules",
            )

    # Style adjective + broad type, no distinguishing name
    if _GENERIC_STYLE_RE.search(text) and subject and _subject_is_broad_type_only(subject):
        return RequestClassification(
            is_specific_real_building=False,
            confidence=0.75,
            reasoning_hint="style + category word only",
            source="rules",
        )

    # Broad alias substring with specific remainder → specific real building
    for word in sorted(_BROAD_TYPE_WORDS, key=len, reverse=True):
        if word.lower() in lower and is_broad_alias(word):
            if has_specific_remainder(lower, word.lower()):
                name = subject or text[:48]
                return RequestClassification(
                    is_specific_real_building=True,
                    building_name_normalized=name,
                    confidence=0.72,
                    reasoning_hint="category word present but intent names a more specific building",
                    source="rules",
                )
            return RequestClassification(
                is_specific_real_building=False,
                confidence=0.78,
                reasoning_hint="broad category word without distinguishing proper name",
                source="rules",
            )

    # Named subject extracted from build verbs
    if subject and len(subject) >= 2 and not _subject_is_broad_type_only(subject):
        conf = 0.68
        if len(subject) >= 4 or re.search(r"[\u4e00-\u9fff]{3,}", subject):
            conf = 0.74
        return RequestClassification(
            is_specific_real_building=True,
            building_name_normalized=subject,
            confidence=conf,
            reasoning_hint="extracted named building subject from user intent",
            source="rules",
        )

    # Longer free-form text that is not clearly generic
    if len(text) >= 6 and not _GENERIC_TYPology_RE.search(text):
        return RequestClassification(
            is_specific_real_building=True,
            building_name_normalized=subject or text[:48],
            confidence=0.55,
            reasoning_hint="ambiguous intent; defaulting to specific for research safety",
            source="rules",
        )

    return RequestClassification(
        is_specific_real_building=False,
        confidence=0.6,
        reasoning_hint="no identifiable specific real-world building",
        source="rules",
    )


def _parse_classification_dict(data: dict) -> Optional[RequestClassification]:
    if not isinstance(data, dict):
        return None
    block = data.get("request_classification")
    if not isinstance(block, dict):
        block = data
    try:
        is_specific = bool(block.get("is_specific_real_building"))
        conf = float(block.get("confidence", 0.5))
        conf = max(0.0, min(1.0, conf))
        name = block.get("building_name_normalized")
        if isinstance(name, str):
            name = name.strip() or None
        else:
            name = None
        hint = block.get("reasoning_hint")
        if isinstance(hint, str):
            hint = hint.strip() or None
        return RequestClassification(
            is_specific_real_building=is_specific,
            building_name_normalized=name,
            confidence=conf,
            reasoning_hint=hint,
            source="llm",
        )
    except (TypeError, ValueError):
        return None


def classify_building_request_with_llm(
    user_text: str,
    *,
    client: Any,
    model: str,
    call_with_timeout: Optional[Callable] = None,
    timeout_sec: float = 5.0,
) -> Optional[RequestClassification]:
    """Lightweight LLM classification — separate from profile synthesis."""
    if client is None or not (user_text or "").strip():
        return None

    system = (
        "Classify a Minecraft build request. Output ONLY JSON:\n"
        "{\n"
        '  "request_classification": {\n'
        '    "is_specific_real_building": boolean,\n'
        '    "building_name_normalized": string or null,\n'
        '    "confidence": number 0-1,\n'
        '    "reasoning_hint": string\n'
        "  }\n"
        "}\n"
        "Rules:\n"
        "- true: user names ONE specific, real, uniquely identifiable built structure "
        "(e.g. Sagrada Família / 圣家族大教堂, Notre-Dame Paris / 巴黎圣母院, Sydney Opera House).\n"
        "- false: generic typology only (a gothic cathedral, 一个大教堂, medieval castle style, 哥特风格教堂).\n"
        "- false: category words alone (大教堂, cathedral, castle) with no distinguishing proper name.\n"
        "- false: purely fictional, unspecified, or patch/edit requests.\n"
        "- building_name_normalized: canonical name + city/country if known; null when false.\n"
        "Do NOT choose generator templates; only classify identity."
    )
    user = f"User request:\n{user_text.strip()}\n\nProduce request_classification JSON."

    def _call():
        return client.chat.completions.create(
            model=model,
            messages=[
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
            response_format={"type": "json_object"},
            temperature=0.0,
        )

    try:
        if call_with_timeout:
            response = call_with_timeout(_call, timeout_sec)
        else:
            response = _call()
        raw = response.choices[0].message.content
        data = json.loads(raw) if isinstance(raw, str) else {}
        return _parse_classification_dict(data)
    except Exception as exc:
        logger.warning("LLM request classification failed: %s", exc)
        return None


def classify_building_request(
    user_text: str,
    *,
    req: Any = None,
    call_with_timeout: Optional[Callable] = None,
    prefer_llm: bool = True,
) -> RequestClassification:
    """
    Stage-1 classification. LLM when available; rules as fallback or tie-breaker on low confidence.
    """
    local = classify_building_request_local(user_text)
    if not is_request_classification_enabled():
        return local

    if not prefer_llm or not is_request_classification_llm_enabled():
        return local

    client = None
    model = "gpt-4o-mini"
    if req is not None:
        try:
            from .llm_client import build_config, get_client

            client = get_client(req)
            cfg = build_config(req, default_model="gpt-4o-mini")
            model = cfg.model
        except Exception:
            client = None

    llm_result = classify_building_request_with_llm(
        user_text,
        client=client,
        model=model,
        call_with_timeout=call_with_timeout,
        timeout_sec=_classification_timeout_sec(),
    )
    if llm_result is None:
        return local

    # High-confidence LLM wins; otherwise merge conservatively
    if llm_result.confidence >= _CLASSIFICATION_MIN_CONFIDENCE:
        if llm_result.is_specific_real_building and not llm_result.building_name_normalized:
            llm_result = llm_result.model_copy(
                update={"building_name_normalized": local.building_name_normalized}
            )
        return llm_result

    if local.confidence >= llm_result.confidence:
        return local

    if llm_result.is_specific_real_building and not llm_result.building_name_normalized:
        llm_result = llm_result.model_copy(
            update={"building_name_normalized": local.building_name_normalized}
        )
    return llm_result


def should_research_for_classification(
    classification: Optional[RequestClassification],
    *,
    has_references: bool = False,
) -> bool:
    """Whether Stage-R web research should run for this intent."""
    if has_references:
        return True
    if classification is None:
        return True
    return classification.is_specific_real_building
