"""Strict landmark alias matching — generic category words vs proper-noun aliases."""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Iterable, Optional, Sequence, Tuple

# 单独命中时不应把更具体的建筑名路由到通用地标
BROAD_ALIASES = frozenset({
    "大教堂",
    "城堡",
    "水镇",
    "神社",
    "茶室",
    "飞艇",
    "树屋",
    "神庙",
    "佛塔",
    "埃菲尔",
    "eiffel",
    "cathedral",
    "castle",
    "shrine",
    "pagoda",
    "stadium",
    "arena",
    "体育馆",
    "体育场",
    "church",
    "寺庙",
    "airship",
    "tea house",
})

PROPER_NOUN_CONFIDENCE = 0.9
GENERIC_TYPOLOGY_CONFIDENCE = 0.4

_REMAINDER_NOISE = re.compile(
    r"[的了一座栋座生成建造帮我请做来个在锚点位置\s\-_,，。！？'\"]+"
)


@dataclass(frozen=True)
class AliasMatchResult:
    matched_alias: str
    confidence: float
    reason_tags: Tuple[str, ...]


def is_broad_alias(alias: str) -> bool:
    if not alias:
        return False
    return alias.strip().lower() in BROAD_ALIASES


def has_specific_remainder(intent_lower: str, matched_alias: str) -> bool:
    if not intent_lower or not matched_alias:
        return False
    remainder = intent_lower.replace(matched_alias, " ").strip()
    remainder = _REMAINDER_NOISE.sub("", remainder).strip()
    return len(remainder) >= 2


def is_explicit_alias_match(intent_lower: str, matched_alias: str) -> bool:
    if not intent_lower or not matched_alias:
        return False
    if not is_broad_alias(matched_alias):
        return True
    return not has_specific_remainder(intent_lower, matched_alias)


def _longest_substring_match(intent_lower: str, aliases: Iterable[str]) -> Optional[str]:
    best: Optional[str] = None
    for alias in aliases:
        if not alias:
            continue
        a = alias.strip().lower()
        if len(a) < 2 or a not in intent_lower:
            continue
        if best is None or len(a) > len(best):
            best = a
    return best


def match_archetype_aliases(
    intent: str,
    proper_aliases: Sequence[str],
    generic_aliases: Sequence[str],
) -> Optional[AliasMatchResult]:
    """Return best alias match with tiered confidence (proper > generic)."""
    if not intent:
        return None
    lower = intent.lower().strip()

    proper = _longest_substring_match(lower, proper_aliases)
    if proper and is_explicit_alias_match(lower, proper):
        return AliasMatchResult(
            matched_alias=proper,
            confidence=PROPER_NOUN_CONFIDENCE,
            reason_tags=("proper_noun", "keyword_match"),
        )

    generic = _longest_substring_match(lower, generic_aliases)
    if generic and is_explicit_alias_match(lower, generic):
        return AliasMatchResult(
            matched_alias=generic,
            confidence=GENERIC_TYPOLOGY_CONFIDENCE,
            reason_tags=("generic_typology",),
        )

    return None


def is_approximate_landmark_match(intent: str, module_id: str, archetype) -> bool:
    """True when intent is more specific than the matched alias for this module."""
    if not intent or not module_id or archetype is None:
        return False
    lower = intent.lower().strip()
    result = match_archetype_aliases(
        lower,
        getattr(archetype, "proper_noun_aliases", ()) or (),
        getattr(archetype, "generic_aliases", ()) or (),
    )
    if result is None:
        return True
    return result.confidence < PROPER_NOUN_CONFIDENCE
