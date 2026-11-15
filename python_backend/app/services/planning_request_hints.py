"""Deterministic request hints used by planning prompts."""

import re
from typing import Optional

from ..models.request import BuildRequest


def _infer_scale(req: BuildRequest) -> str:
    """Infer a coarse cluster scale for planning prompts."""
    text = (req.requestText or "").lower()
    if req.selection is not None:
        dx = abs(req.selection.max.x - req.selection.min.x) + 1
        dz = abs(req.selection.max.z - req.selection.min.z) + 1
        m = max(dx, dz)
        if m <= 48:
            return "SMALL"
        if m <= 96:
            return "MEDIUM"
        return "LARGE"
    if any(k in text for k in ["large", "huge", "gigantic", "大型", "巨型", "超大"]):
        return "LARGE"
    if any(k in text for k in ["small", "tiny", "小型", "迷你"]):
        return "SMALL"
    return "MEDIUM"


def _infer_terrain(req: BuildRequest) -> str:
    """Best-effort terrain hint from user text (we do NOT sample terrain here)."""
    t = (req.requestText or "").lower()
    if any(k in t for k in ["flat", "平坦", "平地", "平原"]):
        return "FLAT"
    if any(k in t for k in ["mountain", "mountainous", "山", "高山", "山地", "峭壁"]):
        return "MOUNTAINOUS"
    if any(k in t for k in ["hill", "hilly", "丘", "丘陵", "起伏", "坡地", "崎岖"]):
        return "HILLY"
    return "UNKNOWN"


def _parse_levels_from_text(s: str) -> Optional[int]:
    if not s:
        return None
    # 支持：7层/七层/层数 7 / levels 7
    m = re.search(r"(?:层数|levels|storeys|stories)\s*(?:为|=|:)?\s*(\d{1,2})", s, flags=re.IGNORECASE)
    if m:
        try:
            v = int(m.group(1))
            return v if v > 0 else None
        except Exception:
            return None
    m = re.search(r"(\d{1,2})\s*(?:层|floor|storey|story)", s, flags=re.IGNORECASE)
    if m:
        try:
            v = int(m.group(1))
            return v if v > 0 else None
        except Exception:
            return None
    # 中文数字（简化：覆盖常用 1~20）
    t = s.lower()
    m = re.search(r"([一二两三四五六七八九十]{1,3})\s*层", t)
    if m:
        cn = m.group(1)
        mapping = {"一": 1, "二": 2, "两": 2, "三": 3, "四": 4, "五": 5, "六": 6, "七": 7, "八": 8, "九": 9, "十": 10}
        if cn in mapping:
            return mapping[cn]
        if cn.startswith("十") and len(cn) == 2 and cn[1] in mapping:
            return 10 + mapping[cn[1]]
        if len(cn) == 2 and cn[0] in mapping and cn[1] == "十":
            return mapping[cn[0]] * 10
        if len(cn) == 3 and cn[0] in mapping and cn[1] == "十" and cn[2] in mapping:
            return mapping[cn[0]] * 10 + mapping[cn[2]]
    return None


def _derive_structured_intent(text: str) -> str:
    """
    B1 意图解析/扩写（规则版）：从（可能稀疏的）用户输入里抽取用途/规模/风格/材质，
    产出一段"结构化意图"软引导，附加到 user prompt 前。纯规则、零额外网络/LLM 调用，
    不阻断体验；解析不到的项让模型用"合理默认"补齐。
    """
    t = (text or "").lower()
    if not t.strip():
        return ""

    def _first(mapping):
        for label, keys in mapping.items():
            for k in keys:
                if k in t:
                    return label
        return None

    purpose = _first({
        "residential house": ["house", "home", "住宅", "房子", "民居", "villa", "别墅"],
        "tower": ["tower", "塔", "spire"],
        "temple / religious": ["temple", "church", "cathedral", "shrine", "寺", "庙", "教堂", "殿"],
        "castle / fortress": ["castle", "fortress", "城堡", "要塞", "堡垒"],
        "bridge": ["bridge", "桥"],
        "wall / fortification": ["wall", "城墙", "长城", "围墙"],
        "commercial / shop": ["shop", "store", "mall", "商", "店铺", "商场"],
        "civic / public": ["hall", "museum", "library", "station", "馆", "厅", "站"],
        "pavilion / garden": ["pavilion", "亭", "阁", "园林", "garden"],
    })
    style = _first({
        "Chinese traditional": ["中式", "中国", "古建", "chinese", "hui", "徽", "jiangnan", "江南", "imperial", "宫"],
        "Gothic": ["哥特", "gothic", "哥特式", "哥特风格"],
        "Modern": ["现代", "modern", "minimal", "curtain wall", "玻璃幕墙"],
        "Classical / European": ["欧式", "古典", "classical", "roman", "greek", "column", "柱"],
        "Rustic / medieval": ["中世纪", "medieval", "rustic", "乡村", "木屋"],
        "Industrial": ["工业", "industrial", "factory", "厂"],
        "Futuristic": ["未来", "futuristic", "sci-fi", "赛博"],
    })
    materials = []
    for label, keys in {
        "wood": ["wood", "木", "timber"],
        "stone/brick": ["stone", "石", "brick", "砖"],
        "glass": ["glass", "玻璃"],
        "concrete": ["concrete", "混凝土"],
        "metal": ["metal", "steel", "钢", "iron", "铁"],
    }.items():
        if any(k in t for k in keys):
            materials.append(label)
    scale = _first({
        "large / monumental": ["huge", "massive", "巨", "宏大", "大型", "grand", "monumental", "tall", "高耸"],
        "small / compact": ["small", "tiny", "小", "compact", "cozy", "袖珍"],
    }) or "medium"

    lines = ["[STRUCTURED INTENT] (auto-derived hint; the user's own text takes priority)"]
    if purpose:
        lines.append(f"- purpose: {purpose}")
    lines.append(f"- scale: {scale}")
    if style:
        lines.append(f"- style: {style}")
    if materials:
        lines.append(f"- materials: {', '.join(materials)}")
    lines.append("- completeness: fill reasonable defaults for anything unspecified; ensure the "
                 "building has an entrance/doors, appropriately sized windows, sane proportions, "
                 "and style-consistent materials.")
    return "\n".join(lines)
