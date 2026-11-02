"""Authoritative request intent — derived only from original user text, never from LLM plan fields."""

from __future__ import annotations

import re
from typing import Any, Dict, List, Optional, Tuple

_TOWER = (
    "佛塔", "古塔", "宝塔", "藏式佛塔", "pagoda", "stupa", "dense_eaves_pagoda", "塔刹", "重檐",
)
_GATE = ("山门", "牌坊", "paifang", "gatehouse", "寺庙山门")
_GARDEN = ("江南园林", "日式庭院", "园林", "庭院", "garden", "courtyard compound")
_TEMPLE = ("道观", "寺庙", "庙宇", "daoist temple", "shrine compound")
_ROOF_EXPLICIT = (
    ("歇山", "xieshan"),
    ("庑殿", "wudian"),
    ("重檐", "double_eave_hip"),
    ("攒尖", "pyramid"),
    ("悬山", "xuanshan"),
    ("硬山", "yingshan"),
    ("穹顶", "dome"),
    ("平顶", "flat"),
    ("双坡", "gable"),
    ("人字", "gable"),
    ("hip roof", "hip"),
    ("gable", "gable"),
)
_NO_WINDOWS = ("不要窗", "无窗", "禁窗", "不开窗", "no windows", "without windows")
_NO_DOORS = ("不要门", "无门", "禁门", "no door", "without door")
_FOOTPRINT = re.compile(r"(\d{1,2})\s*[×xX*]\s*(\d{1,2})")


def extract_request_intent(user_text: str) -> Dict[str, Any]:
    """Build a server-owned intent snapshot from the player request only."""
    text = user_text or ""
    lower = text.lower()
    building_class = "generic"
    if any(m.lower() in lower or m in text for m in _TOWER):
        building_class = "tower_like"
    elif any(m.lower() in lower or m in text for m in _GATE):
        building_class = "gate"
    elif any(m.lower() in lower or m in text for m in _GARDEN):
        building_class = "garden"
    elif any(m.lower() in lower or m in text for m in _TEMPLE):
        building_class = "temple"

    roof_type = None
    for phrase, canonical in _ROOF_EXPLICIT:
        if phrase.lower() in lower or phrase in text:
            roof_type = canonical
            break

    width = depth = 0
    for match in _FOOTPRINT.finditer(text):
        a, b = int(match.group(1)), int(match.group(2))
        if 4 <= a <= 48 and 4 <= b <= 48:
            width, depth = a, b
            break

    forbids: List[str] = []
    if any(p.lower() in lower or p in text for p in _NO_WINDOWS):
        forbids.append("windows")
    if any(p.lower() in lower or p in text for p in _NO_DOORS):
        forbids.append("entrance")

    materials: Dict[str, str] = {}
    if "白墙" in text or "white wall" in lower or "white_concrete" in lower:
        materials["wall"] = "minecraft:white_concrete"
    if "黑瓦" in text or "black tile" in lower or "black_terracotta" in lower:
        materials["roof"] = "minecraft:black_terracotta"
    if "石砖" in text or "stone brick" in lower:
        materials.setdefault("wall", "minecraft:stone_bricks")

    markers: List[str] = []
    for group in (_TOWER, _GATE, _GARDEN, _TEMPLE):
        for m in group:
            if m.lower() in lower or m in text:
                markers.append(m)

    return {
        "schema": "formacraft.request_intent.v1",
        "source": "user_request",
        "original_text": text,
        "building_class": building_class,
        "markers": markers[:12],
        "roof_type": roof_type,
        "roof_explicit": roof_type is not None,
        "footprint": {"width": width, "depth": depth} if width and depth else None,
        "forbids": forbids,
        "materials": materials,
        "refuse_residential_autofill": building_class != "generic",
    }


def attach_request_intent(plan: Dict[str, Any], user_text: str) -> Dict[str, Any]:
    """Write/overwrite plan_meta.request_intent from user text (authoritative)."""
    if not isinstance(plan, dict):
        return plan
    intent = extract_request_intent(user_text or "")
    meta = plan.get("plan_meta")
    if not isinstance(meta, dict):
        meta = {}
    else:
        meta = dict(meta)
    meta["request_intent"] = intent
    # Keep a short echo for Java validators that only scan strings.
    meta["request_intent_text"] = intent.get("original_text") or ""
    meta["building_class"] = intent.get("building_class")
    plan["plan_meta"] = meta

    hints = plan.get("proportion_hints")
    if not isinstance(hints, dict):
        hints = {}
    else:
        hints = dict(hints)
    # Preserve architecture_intent if present; always ensure original_text exists.
    arch = hints.get("architecture_intent")
    if not isinstance(arch, dict):
        from .architecture_intent import describe_architecture
        arch = describe_architecture(user_text or "")
    else:
        arch = dict(arch)
        if not str(arch.get("original_text") or "").strip():
            arch["original_text"] = user_text or ""
    hints["architecture_intent"] = arch
    plan["proportion_hints"] = hints
    return plan


def scaffold_plan_for_intent(
    intent: Dict[str, Any],
    *,
    anchor: Optional[Dict[str, int]] = None,
) -> Optional[Dict[str, Any]]:
    """Deterministic typology-aware scaffold when LLM plan fails semantic checks."""
    building_class = str(intent.get("building_class") or "generic")
    fp = intent.get("footprint") if isinstance(intent.get("footprint"), dict) else {}
    width = int(fp.get("width") or 0) or 7
    depth = int(fp.get("depth") or 0) or width
    materials = intent.get("materials") if isinstance(intent.get("materials"), dict) else {}
    wall = materials.get("wall") or "minecraft:white_concrete"
    roof = materials.get("roof") or "minecraft:dark_oak_stairs"
    origin = anchor or {"x": 0, "y": 64, "z": 0}
    style_attrs = {
        "wall_material": wall,
        "roof_material": roof,
        "palette": {"wall": wall, "roof": roof},
    }
    forbids = list(intent.get("forbids") or [])
    markers = list(intent.get("markers") or [])

    if building_class == "tower_like":
        height = max(18, width * 3)
        levels = 3 if ("三层" in str(intent.get("original_text") or "") or "3层" in str(intent.get("original_text") or "")) else 5
        return {
            "mode": "build",
            "style_profile": "DEFAULT",
            "anchor": origin,
            "distinguishing_features": markers or ["pagoda", "dense_eaves_pagoda"],
            "style_attributes": style_attrs,
            "components": [
                {
                    "component_type": "STRUCTURE",
                    "slot_id": "tower",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": width, "depth": depth, "height": height},
                    "features": ["dense_eaves_pagoda", "finial_spire", "塔刹"],
                        "params": {
                        "typology": "dense_eaves_pagoda",
                        "levels": levels,
                        "eave_tiers": levels,
                        "wall_block": wall,
                        "roof_block": roof,
                        **({"window_style": "none", "window_ratio": 0} if "windows" in forbids else {}),
                    },
                }
            ],
            "plan_meta": {
                "request_intent": intent,
                "building_class": "tower_like",
                "scaffolded_from_request_intent": True,
                "allow_compiler_satellites": False,
            },
        }

    if building_class == "gate":
        w = max(12, width if width >= depth else width * 2)
        d = max(4, min(depth, w // 2))
        return {
            "mode": "build",
            "style_profile": "DEFAULT",
            "anchor": origin,
            "distinguishing_features": markers or ["山门"],
            "style_attributes": style_attrs,
            "components": [
                {
                    "component_type": "STRUCTURE",
                    "slot_id": "gate",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": w, "depth": d, "height": max(8, d * 2)},
                    "features": ["temple_gate", "山门", "paifang"],
                    "params": {
                        "wall_block": wall,
                        "roof_block": roof,
                        "roof_type": intent.get("roof_type") or "xieshan",
                        "passage_axis": "z",
                    },
                }
            ],
            "plan_meta": {
                "request_intent": intent,
                "building_class": "gate",
                "scaffolded_from_request_intent": True,
                "allow_compiler_satellites": False,
            },
        }

    if building_class in ("garden", "temple"):
        site_w = max(16, width * 2)
        site_d = max(16, depth * 2)
        comps: List[Dict[str, Any]] = [
            {
                "component_type": "COURTYARD",
                "slot_id": "yard",
                "relative_position": {"x": 2, "y": 0, "z": 2},
                "dimensions": {"width": site_w - 4, "depth": site_d - 4, "height": 1},
                "features": ["courtyard"],
                "params": {},
            },
            {
                "component_type": "PATH",
                "slot_id": "path",
                "relative_position": {"x": site_w // 2, "y": 0, "z": 0},
                "dimensions": {"width": 3, "depth": site_d, "height": 1},
                "features": ["axis_path"],
                "params": {},
            },
            {
                "component_type": "MASS_MAIN",
                "slot_id": "hall",
                "relative_position": {"x": site_w // 4, "y": 0, "z": site_d // 3},
                "dimensions": {"width": max(7, site_w // 3), "depth": max(5, site_d // 4), "height": 6},
                "features": ["main_hall"],
                "params": {
                    "wall_block": wall,
                    "roof_block": roof,
                    "roof_type": intent.get("roof_type") or "xieshan",
                    **({"window_style": "none", "window_ratio": 0} if "windows" in forbids else {}),
                },
            },
        ]
        return {
            "mode": "build",
            "style_profile": "DEFAULT",
            "anchor": origin,
            "distinguishing_features": markers or [building_class],
            "style_attributes": style_attrs,
            "components": comps,
            "plan_meta": {
                "request_intent": intent,
                "building_class": building_class,
                "scaffolded_from_request_intent": True,
                "allow_compiler_satellites": False,
            },
        }

    return None
