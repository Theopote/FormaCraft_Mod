"""Structural typology retrieval for plan / RAG stages."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Dict, List, Optional

from .typology_registry import (
    TypologyDef,
    get_migration,
    get_typology,
    list_typologies,
    typology_for_archetype,
    typology_for_culture_card,
    typology_for_legacy_module,
)


@dataclass(frozen=True)
class TypologyMatch:
    typology_id: str
    score: float
    source: str
    matched_keywords: List[str]
    reference_landmark_id: Optional[str] = None
    legacy_module_id: Optional[str] = None
    routing_policy: str = "typology_first"


def _score_keywords(text: str, keywords: tuple[str, ...], negatives: tuple[str, ...]) -> tuple[float, list[str]]:
    q = (text or "").strip().lower()
    if not q:
        return 0.0, []
    score = 0.0
    matched: List[str] = []
    for kw in keywords:
        kl = kw.lower()
        if kl in q:
            matched.append(kw)
            score += max(2.0, len(kw) * 0.5)
    for nk in negatives:
        if nk.lower() in q:
            score -= max(2.0, len(nk) * 0.4)
    return score, matched


def _primary_reference_landmark(defn: TypologyDef) -> Optional[str]:
    for ref in defn.reference_landmarks:
        if ref.role == "primary":
            return ref.archetype_id
    if defn.reference_landmarks:
        return defn.reference_landmarks[0].archetype_id
    return None


def resolve_typology_for_intent(
    user_text: str,
    *,
    culture_card_id: Optional[str] = None,
    archetype_id: Optional[str] = None,
    legacy_module_id: Optional[str] = None,
) -> Optional[TypologyMatch]:
    """
    Resolve structural typology from explicit ids (culture/archetype/legacy) or keyword scoring.
    """
    if legacy_module_id:
        tid = typology_for_legacy_module(legacy_module_id)
        if tid:
            defn = get_typology(tid)
            if defn:
                mig = get_migration(legacy_module_id)
                return TypologyMatch(
                    typology_id=tid,
                    score=10.0,
                    source="legacy_module",
                    matched_keywords=[legacy_module_id],
                    reference_landmark_id=_primary_reference_landmark(defn),
                    legacy_module_id=mig.legacy_module_id if mig else legacy_module_id,
                    routing_policy=defn.routing_policy,
                )

    if culture_card_id:
        tid = typology_for_culture_card(culture_card_id)
        if tid:
            defn = get_typology(tid)
            if defn:
                return TypologyMatch(
                    typology_id=tid,
                    score=9.0,
                    source="culture_card",
                    matched_keywords=[culture_card_id],
                    reference_landmark_id=culture_card_id,
                    legacy_module_id=defn.legacy_interpreter_id,
                    routing_policy=defn.routing_policy,
                )

    if archetype_id:
        tid = typology_for_archetype(archetype_id)
        if tid:
            defn = get_typology(tid)
            if defn:
                return TypologyMatch(
                    typology_id=tid,
                    score=8.5,
                    source="archetype",
                    matched_keywords=[archetype_id],
                    reference_landmark_id=archetype_id,
                    legacy_module_id=defn.legacy_interpreter_id,
                    routing_policy=defn.routing_policy,
                )

    q = user_text or ""
    best: Optional[TypologyMatch] = None
    best_score = 0.0
    for defn in list_typologies():
        score, matched = _score_keywords(q, defn.match_keywords, defn.negative_keywords)
        if score > best_score:
            best_score = score
            best = TypologyMatch(
                typology_id=defn.id,
                score=score,
                source="keyword",
                matched_keywords=matched,
                reference_landmark_id=_primary_reference_landmark(defn),
                legacy_module_id=defn.legacy_interpreter_id,
                routing_policy=defn.routing_policy,
            )
    return best if best_score > 0.01 else None


def typology_prompt_block(match: Optional[TypologyMatch]) -> str:
    if match is None:
        return ""
    defn = get_typology(match.typology_id)
    if defn is None:
        return ""
    lines = [
        "",
        "========================================",
        "STRUCTURAL TYPOLOGY (parametric interpreter)",
        "========================================",
        f"structural_typology: {defn.id} ({defn.display_name_zh})",
        f"skeleton_type: {defn.skeleton_type}",
        f"routing_policy: {defn.routing_policy}",
    ]
    if match.reference_landmark_id:
        lines.append(f"reference_landmark (proportion anchor only): {match.reference_landmark_id}")
    if defn.default_params:
        lines.append("default_params: " + ", ".join(f"{k}={v}" for k, v in defn.default_params.items()))
    if defn.llm_plan_guidance:
        lines.append(defn.llm_plan_guidance.strip())
    lines.append(
        "You MUST set proportion_hints.typology to this typology id and "
        "layout.skeleton_type to the skeleton above."
    )
    return "\n".join(lines)


def compact_typology_payload(match: Optional[TypologyMatch]) -> Optional[Dict[str, Any]]:
    if match is None:
        return None
    defn = get_typology(match.typology_id)
    if defn is None:
        return None
    return {
        "structuralTypologyId": defn.id,
        "skeletonType": defn.skeleton_type,
        "routingPolicy": defn.routing_policy,
        "referenceLandmarkId": match.reference_landmark_id,
        "legacyModuleId": match.legacy_module_id,
        "defaultParams": defn.default_params,
        "matchedKeywords": match.matched_keywords,
        "source": match.source,
        "score": match.score,
    }


def should_suppress_landmark_module(match: Optional[TypologyMatch]) -> bool:
    """typology_first policy: do not auto-inject landmark MODULE when typology resolves."""
    if match is None:
        return False
    return match.routing_policy == "typology_first"
