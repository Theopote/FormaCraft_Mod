from __future__ import annotations

from dataclasses import dataclass
from typing import List, Optional, Tuple

from .archetype_registry import all_archetypes, list_archetype_ids
from .landmark_alias_matcher import (
    GENERIC_TYPOLOGY_CONFIDENCE,
    PROPER_NOUN_CONFIDENCE,
    match_archetype_aliases,
)

MODULE_ROUTE_MIN_CONFIDENCE = 0.85


@dataclass(frozen=True)
class ArchetypeMatch:
    id: str
    confidence: float
    reason_tags: Tuple[str, ...] = ()
    matched_alias: Optional[str] = None

    def qualifies_for_module_route(self) -> bool:
        """High-confidence proper-noun hit with a preset MODULE generator only."""
        if self.confidence < MODULE_ROUTE_MIN_CONFIDENCE:
            return False
        if "generic_typology" in self.reason_tags:
            return False
        try:
            from .archetype_registry import get_archetype_def

            defn = get_archetype_def(self.id)
            if defn is None or defn.research_only or not defn.generator_id:
                return False
        except Exception:
            return False
        return True


def detect_archetype_local(text: str) -> Optional[ArchetypeMatch]:
    """
    Stage 1: local fast matching with tiered confidence.
    - properNounAliases → 0.9 (can trigger MODULE route)
    - genericAliases only → 0.4 (typology hint, never alone routes MODULE)
    """
    if not text:
        return None
    s = text.lower().strip()
    best: Optional[ArchetypeMatch] = None

    for arch in all_archetypes():
        result = match_archetype_aliases(
            s,
            arch.proper_noun_aliases,
            arch.generic_aliases,
        )
        if result is None:
            continue
        candidate = ArchetypeMatch(
            id=arch.id,
            confidence=result.confidence,
            reason_tags=result.reason_tags,
            matched_alias=result.matched_alias,
        )
        if best is None:
            best = candidate
            continue
        if candidate.confidence > best.confidence:
            best = candidate
        elif (
            candidate.confidence == best.confidence
            and candidate.matched_alias
            and (best.matched_alias is None or len(candidate.matched_alias) > len(best.matched_alias))
        ):
            best = candidate

    return best


def candidate_list() -> List[str]:
    """
    Candidate list for optional AI confirmation (Stage 2).
    """
    return list_archetype_ids() + ["none"]


def should_force_strong_mode(text: str) -> bool:
    """
    User explicit intent for strong resemblance.
    """
    if not text:
        return False
    s = text.lower()
    return any(
        k in s
        for k in (
            "尽量还原", "强还原", "像一点", "像", "真实比例", "高还原", "还原度",
            "按原型", "尽量真实",
        )
    )
