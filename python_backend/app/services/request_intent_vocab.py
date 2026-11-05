"""Load shared request_intent vocabulary (Java + Python single source)."""

from __future__ import annotations

import json
from functools import lru_cache
from pathlib import Path
from typing import Any, Dict


_REPO_ROOT = Path(__file__).resolve().parents[3]
_VOCAB_PATH = (
    _REPO_ROOT
    / "src"
    / "main"
    / "resources"
    / "assets"
    / "formacraft"
    / "request_intent"
    / "request_intent_vocab_v1.json"
)


@lru_cache(maxsize=1)
def load_request_intent_vocab() -> Dict[str, Any]:
    if not _VOCAB_PATH.is_file():
        return _fallback_vocab()
    with _VOCAB_PATH.open("r", encoding="utf-8") as fh:
        data = json.load(fh)
    if not isinstance(data, dict):
        return _fallback_vocab()
    return data


def _fallback_vocab() -> Dict[str, Any]:
    """Minimal inline fallback if asset file is missing at runtime."""
    return {
        "schema": "formacraft.request_intent_vocab.v1",
        "negation_prefixes": [
            "不要", "别要", "无需", "不用", "不采用", "不使用", "禁止", "避免", "无",
            "no ", "without ", "not ",
        ],
        "building_classes": {
            "tower_like": {
                "strong_markers": [
                    "藏式佛塔", "佛塔", "古塔", "宝塔", "pagoda", "stupa", "塔刹",
                ],
                "weak_markers": [],
            },
            "gate": {
                "strong_markers": ["山门", "牌坊", "paifang", "gatehouse", "寺庙山门"],
            },
            "garden": {
                "strong_markers": ["江南园林", "日式庭院", "园林", "courtyard compound"],
                "weak_markers": ["庭院", "courtyard", "garden"],
                "weak_suppressors": [
                    "别墅", "villa", "城堡", "castle", "住宅", "house", "图书馆", "library",
                ],
            },
            "temple": {
                "strong_markers": ["道观", "寺庙", "庙宇", "大殿", "殿堂", "daoist temple"],
            },
        },
        "roof_types": [
            {"phrase": "歇山", "canonical": "xieshan"},
            {"phrase": "庑殿", "canonical": "wudian"},
            {"phrase": "重檐", "canonical": "double_eave_hip"},
            {"phrase": "攒尖", "canonical": "pyramid"},
            {"phrase": "悬山", "canonical": "xuanshan"},
            {"phrase": "硬山", "canonical": "yingshan"},
            {"phrase": "平顶", "canonical": "flat"},
            {"phrase": "双坡", "canonical": "gable"},
            {"phrase": "gable", "canonical": "gable"},
        ],
        "forbids": {
            "windows": ["不要窗", "无窗", "禁窗", "不开窗", "no windows", "without windows"],
            "entrance": ["不要门", "无门", "禁门", "no door", "without door"],
        },
        "forbid_false_friends": {
            "entrance": ["无门槛", "没门槛", "不设门槛", "no threshold", "without threshold"],
        },
        "tower_subtypes": {
            "tibetan_stupa": ["藏式", "白塔", "覆钵", "相轮", "喇嘛塔", "tibetan", "chorten"],
            "dense_eaves_pagoda": ["密檐", "古塔", "宝塔", "pagoda"],
        },
        "temple_compound_markers": ["建筑群", "院落", "厢房", "配殿", "三进", "两进", "宫观群"],
        "materials": {
            "wall": [
                {"phrases": ["白墙", "white wall", "white_concrete"], "block": "minecraft:white_concrete"},
                {"phrases": ["石砖", "stone brick"], "block": "minecraft:stone_bricks"},
            ],
            "roof": [
                {"phrases": ["黑瓦", "black tile", "black_terracotta"], "block": "minecraft:black_terracotta"},
            ],
        },
        "confidence": {
            "strong_hit": 0.9,
            "weak_hit": 0.45,
            "llm_preferred_min": 0.55,
            "keyword_override_min": 0.8,
            "scaffold_min": 0.75,
        },
    }
