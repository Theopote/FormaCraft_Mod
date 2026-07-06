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


# 用户明确拒绝地标 MODULE（优先于别名子串匹配，避免「不要鸟巢」误命中「鸟巢」）
LANDMARK_REJECTION_MARKERS: List[str] = [
    "不要地标", "不要鸟巢", "不要仿", "不要复制", "别照搬", "非地标",
    "not landmark", "not a landmark", "no landmark",
    "don't copy", "do not copy",
]

# 多样化/原创措辞——仅在没有指名地标时，阻止 typology SUGGESTED 联想
VARIATION_INTENT_MARKERS: List[str] = [
    "原创", "独创", "独特", "不一样", "不要一样", "每次不同", "创新", "想象", "自由发挥",
    "自行设计", "自己设计",
    "generic", "original", "unique", "creative", "imaginative", "custom design",
    "varied", "different each time",
]

# 兼容旧 API：拒绝 + 多样化均视为「非强制路由」信号
CREATIVE_INTENT_MARKERS: List[str] = LANDMARK_REJECTION_MARKERS + VARIATION_INTENT_MARKERS

BIRDS_NEST_EXPLICIT: List[str] = [
    "鸟巢", "鸟巢体育馆", "国家体育场", "北京鸟巢",
    "bird's nest", "birds nest", "birds' nest", "beijing national stadium",
]

GOTHIC_CATHEDRAL_EXPLICIT: List[str] = [
    "哥特大教堂", "哥特式大教堂", "gothic cathedral",
]

MINGQING_COURTYARD_EXPLICIT: List[str] = [
    "明清官式院落", "明清官式建筑群落", "明清四合院",
    "ming qing courtyard", "mingqing courtyard",
]

CASTLE_COMPOUND_EXPLICIT: List[str] = [
    "中世纪城堡", "城堡复合体",
    "medieval castle compound", "medieval castle", "castle compound",
]

STADIUM_TYPOLOGY: List[str] = ["体育场", "体育馆", "stadium", "arena", "球场"]
ELLIPSE_TYPOLOGY: List[str] = ["椭圆", "椭圆形", "elliptical", "oval", "碗状", "看台"]

NAMED_ARCHITECT_MARKERS: List[str] = [
    "扎哈", "zaha", "哈迪德", "hadid",
    "贝聿铭", "i.m. pei", "pei",
    "安藤忠雄", "tadao ando",
    "诺曼·福斯特", "norman foster",
    "伦佐·皮亚诺", "renzo piano",
    "让·努维尔", "jean nouvel",
]

VARIATION_HINTS: List[str] = [
    "Vary facade_profile, entrance count, roof_type, masses[] offsets each generation.",
    "For MODULE: vary designSeed (1-9999), facing, bowlSteepness (0.25-0.45), dimensions hints.",
    "For 原创/独特 intent: use compositional MASS + PAVING + ROOF, not identical MODULE clones.",
]


def _contains_any(text: str, markers: List[str]) -> bool:
    lower = text.lower()
    return any(m.lower() in lower for m in markers)


def rejects_landmark_module(text: str) -> bool:
    if not (text or "").strip():
        return False
    return _contains_any(text, LANDMARK_REJECTION_MARKERS)


def is_variation_intent(text: str) -> bool:
    if not (text or "").strip():
        return False
    return _contains_any(text, VARIATION_INTENT_MARKERS)


def is_creative_or_original_intent(text: str) -> bool:
    """拒绝地标 或 仅要原创/多样化（无指名地标时阻止 SUGGESTED typology）。"""
    return rejects_landmark_module(text) or is_variation_intent(text)


def resolve_for_user_intent(text: str) -> Optional[RoutingDecision]:
    """
    指名地标（MANDATORY）优先于多样化措辞；
    多样化/拒绝仅在没有指名地标时阻止 typology SUGGESTED。
    """
    q = (text or "").strip()
    if not q:
        return None

    if rejects_landmark_module(q):
        return None

    lower = q.lower()

    # 指名鸟巢 — 即使同时含「不一样」等多样化词，仍强制 MODULE
    if _contains_any(lower, BIRDS_NEST_EXPLICIT):
        return RoutingDecision("birds_nest_stadium", RoutingTier.MANDATORY, "explicit_birds_nest")

    if _contains_any(lower, GOTHIC_CATHEDRAL_EXPLICIT) and not any(
        x in lower for x in ("notre dame", "巴黎圣母院", "cologne", "科隆", "chartres", "sagrada", "圣家族")
    ):
        return RoutingDecision("gothic_cathedral", RoutingTier.MANDATORY, "explicit_gothic_cathedral")

    if _contains_any(lower, MINGQING_COURTYARD_EXPLICIT):
        return RoutingDecision("mingqing_courtyard", RoutingTier.MANDATORY, "explicit_mingqing_courtyard")

    if _contains_any(lower, CASTLE_COMPOUND_EXPLICIT) and not any(
        x in lower for x in ("disney", "迪士尼", "himeji", "姬路", "japanese castle", "日式")
    ):
        return RoutingDecision("castle_compound", RoutingTier.MANDATORY, "explicit_castle_compound")

    # 点名建筑师 + 体育场 → 参数化形体，禁止默认 birds_nest typology
    if _contains_any(lower, NAMED_ARCHITECT_MARKERS) and _contains_any(lower, STADIUM_TYPOLOGY):
        return None

    # 无指名地标时的原创/多样化 → 不做 typology 联想
    if is_variation_intent(q):
        return None

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
