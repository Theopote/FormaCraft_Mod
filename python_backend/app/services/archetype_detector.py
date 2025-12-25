from __future__ import annotations

from dataclasses import dataclass
from typing import List, Optional, Tuple

from .archetype_registry import alias_index, list_archetype_ids


@dataclass(frozen=True)
class ArchetypeMatch:
    id: str
    confidence: float
    reason_tags: Tuple[str, ...] = ()
    matched_alias: Optional[str] = None


def detect_archetype_local(text: str) -> Optional[ArchetypeMatch]:
    """
    Stage 1: local fast/robust matching.
    - keyword/alias hit -> high confidence
    """
    if not text:
        return None
    s = text.lower()
    _ALIAS_MAP = alias_index()
    hits: List[Tuple[str, str]] = []  # (alias, id)
    for alias, aid in _ALIAS_MAP.items():
        if alias and alias in s:
            hits.append((alias, aid))
    if not hits:
        return None

    # prefer longest alias match (more specific)
    hits.sort(key=lambda x: len(x[0]), reverse=True)
    alias, aid = hits[0]
    # base confidence: alias hit is strong
    conf = 0.9
    tags = ("keyword_match",)
    return ArchetypeMatch(id=aid, confidence=conf, reason_tags=tags, matched_alias=alias)


def candidate_list() -> List[str]:
    """
    Candidate list for optional AI confirmation (Stage 2).
    """
    # include none as explicit option to prevent hallucination
    return list_archetype_ids() + ["none"]


def should_force_strong_mode(text: str) -> bool:
    """
    User explicit intent for strong resemblance.
    """
    if not text:
        return False
    s = text.lower()
    return any(k in s for k in ("尽量还原", "强还原", "像一点", "像", "真实比例", "高还原", "还原度", "按原型", "尽量真实"))


