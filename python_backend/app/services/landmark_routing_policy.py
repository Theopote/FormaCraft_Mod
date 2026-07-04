"""Landmark MODULE routing tiers — mirrors Java LandmarkRoutingPolicy."""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import List, Optional


class RoutingTier(str, Enum):
    NONE = "none"
    SUGGESTED = "suggested"
    MANDATORY = "mandatory"


@dataclass(frozen=True)
class RoutingDecision:
    module_id: str
    tier: RoutingTier
    reason: str

    @property
    def applies(self) -> bool:
        return bool(self.module_id) and self.tier != RoutingTier.NONE


CREATIVE_INTENT_MARKERS: List[str] = [
    "原创", "独创", "独特", "不一样", "不要一样", "每次不同", "创新", "想象", "自由发挥",
    "不要地标", "不要鸟巢", "不要仿", "不要复制", "别照搬", "非地标", "自行设计", "自己设计",
    "generic", "original", "unique", "creative", "imaginative", "custom design",
    "don't copy", "do not copy", "not landmark", "not a landmark", "one of a kind",
    "varied", "different each time", "no landmark",
]

BIRDS_NEST_EXPLICIT: List[str] = [
    "鸟巢", "鸟巢体育馆", "国家体育场", "北京鸟巢",
    "bird's nest", "birds nest", "birds' nest", "beijing national stadium",
]

STADIUM_TYPOLOGY: List[str] = ["体育场", "体育馆", "stadium", "arena", "球场"]
ELLIPSE_TYPOLOGY: List[str] = ["椭圆", "椭圆形", "elliptical", "oval", "碗状", "看台"]

VARIATION_HINTS: List[str] = [
    "Vary facade_profile, entrance count, roof_type, masses[] offsets each generation.",
    "For MODULE: vary designSeed (1-9999), facing, bowlSteepness (0.25-0.45), dimensions hints.",
    "For 原创/独特 intent: use compositional MASS + PAVING + ROOF, not identical MODULE clones.",
]


def _contains_any(text: str, markers: List[str]) -> bool:
    lower = text.lower()
    return any(m.lower() in lower for m in markers)


def is_creative_or_original_intent(text: str) -> bool:
    if not (text or "").strip():
        return False
    return _contains_any(text, CREATIVE_INTENT_MARKERS)


def resolve_for_user_intent(text: str) -> Optional[RoutingDecision]:
    q = (text or "").strip()
    if not q:
        return None
    if is_creative_or_original_intent(q):
        return None

    lower = q.lower()

    if _contains_any(lower, BIRDS_NEST_EXPLICIT):
        return RoutingDecision("birds_nest_stadium", RoutingTier.MANDATORY, "explicit_birds_nest")

    stadium = _contains_any(lower, STADIUM_TYPOLOGY)
    elliptical = _contains_any(lower, ELLIPSE_TYPOLOGY)
    if stadium and elliptical:
        return RoutingDecision(
            "birds_nest_stadium", RoutingTier.SUGGESTED, "typological_elliptical_stadium"
        )
    if stadium and ("现代" in lower or "modern" in lower):
        return RoutingDecision(
            "birds_nest_stadium", RoutingTier.SUGGESTED, "typological_modern_stadium"
        )

    return None


def variation_context_block() -> str:
    lines = ["[BUILDING VARIATION] (apply to every plan — users expect distinct results)"]
    lines.extend(f"- {h}" for h in VARIATION_HINTS)
    return "\n".join(lines)
