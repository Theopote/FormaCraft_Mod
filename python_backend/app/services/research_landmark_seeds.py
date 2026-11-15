"""
Curated distinguishing-feature seeds for researchOnly landmarks.

Injected into BuildingProfile when web research is thin or LLM synthesis omits
building-specific traits — prevents generic style keywords from dominating.
"""

from __future__ import annotations

from typing import Any, Dict, List, Optional, Tuple

from ..models.building_profile import BuildingProfile

# archetype_id → seed payload
RESEARCH_LANDMARK_SEEDS: Dict[str, Dict[str, Any]] = {
    "sagrada_familia": {
        "display_name": "Sagrada Família (圣家族大教堂)",
        "architect": "Antoni Gaudí",
        "style": "Modernisme",
        "distinguishing_features": [
            "hyperboloid parabolic bell towers",
            "tree-like branching interior columns",
            "mosaic trencadís ceramic facade",
            "organic curved stone surfaces",
            "nativity facade sculptural portals",
        ],
        "scale_hints": {"typical_height_blocks": 80, "typical_width_blocks": 45, "typical_depth_blocks": 90},
    },
    "notre_dame_paris": {
        "display_name": "Notre-Dame de Paris (巴黎圣母院)",
        "style": "French Gothic",
        "distinguishing_features": [
            "twin western bell towers",
            "pointed arch triple portal west facade",
            "flying buttresses along choir",
            "rose windows",
            "gargoyle rainwater spouts",
        ],
        "scale_hints": {"typical_height_blocks": 55, "typical_width_blocks": 40, "typical_depth_blocks": 100},
    },
    "cologne_cathedral": {
        "display_name": "Cologne Cathedral (科隆大教堂)",
        "style": "German Gothic",
        "distinguishing_features": [
            "twin spires with openwork pinnacles",
            "massive longitudinal nave",
            "rich sculptural west facade",
            "polygonal chevet apse",
            "very tall vertical proportions",
        ],
        "scale_hints": {"typical_height_blocks": 95, "typical_width_blocks": 35, "typical_depth_blocks": 110},
    },
    "chartres_cathedral": {
        "display_name": "Chartres Cathedral (沙特尔主教座堂)",
        "style": "High Gothic",
        "distinguishing_features": [
            "two contrasting spires (plain and ornate)",
            "royal portal sculptural program",
            "large stained-glass rose windows",
            "pilgrimage cathedral scale",
            "flying buttress rhythm",
        ],
        "scale_hints": {"typical_height_blocks": 70, "typical_width_blocks": 38, "typical_depth_blocks": 95},
    },
    "sydney_opera_house": {
        "display_name": "Sydney Opera House (悉尼歌剧院)",
        "architect": "Jørn Utzon",
        "style": "Expressionist Modern",
        "distinguishing_features": [
            "sail-like precast shell roof vaults",
            "triangulated ceramic tile cladding",
            "podium terrace overlooking harbour",
            "clustered overlapping shells",
            "dramatic waterfront setting",
        ],
        "scale_hints": {"typical_height_blocks": 35, "typical_width_blocks": 80, "typical_depth_blocks": 55},
    },
    "louvre_museum": {
        "display_name": "Louvre Museum (卢浮宫)",
        "style": "French Classical / Modern insert",
        "distinguishing_features": [
            "Renaissance palace courtyard wings",
            "glass pyramid entrance pavilion",
            "long axial palace facade",
            "pavilions and colonnaded passages",
            "grande scale urban block",
        ],
        "scale_hints": {"typical_height_blocks": 28, "typical_width_blocks": 120, "typical_depth_blocks": 80},
    },
    "suzhou_museum": {
        "display_name": "Suzhou Museum (苏州博物馆)",
        "architect": "I.M. Pei",
        "style": "Modern Chinese",
        "distinguishing_features": [
            "white stucco walls with grey stone base",
            "central glass roof pavilion",
            "geometric lattice windows",
            "water courtyard reflection pool",
            "angled modern volumes in garden",
        ],
        "scale_hints": {"typical_height_blocks": 16, "typical_width_blocks": 50, "typical_depth_blocks": 40},
    },
    "forbidden_city": {
        "display_name": "Forbidden City (故宫)",
        "style": "Chinese Imperial",
        "distinguishing_features": [
            "meridian gate axial symmetry",
            "yellow glazed tile roofs",
            "red lacquered walls and columns",
            "progression of courtyards",
            "hall of supreme harmony raised terrace",
        ],
        "scale_hints": {"typical_height_blocks": 20, "typical_width_blocks": 140, "typical_depth_blocks": 100},
    },
    "taj_mahal": {
        "display_name": "Taj Mahal (泰姬陵)",
        "style": "Mughal",
        "distinguishing_features": [
            "white marble mausoleum on raised plinth",
            "central onion dome with finial",
            "four minarets at corners",
            "symmetrical charbagh garden axis",
            "inlaid pietra dura floral panels",
        ],
        "scale_hints": {"typical_height_blocks": 45, "typical_width_blocks": 50, "typical_depth_blocks": 50},
    },
    "white_house": {
        "display_name": "White House (白宫)",
        "style": "Neoclassical",
        "distinguishing_features": [
            "colonnaded north portico",
            "balanced Palladian facade",
            "white painted sandstone walls",
            "semicircular south portico",
            "low horizontal executive mansion massing",
        ],
        "scale_hints": {"typical_height_blocks": 18, "typical_width_blocks": 45, "typical_depth_blocks": 30},
    },
    "guggenheim_bilbao": {
        "display_name": "Guggenheim Museum Bilbao (毕尔巴鄂古根海姆)",
        "architect": "Frank Gehry",
        "style": "Deconstructivism",
        "distinguishing_features": [
            "titanium-clad curved volumes",
            "sculptural fish-scale metal skin",
            "riverfront twisting massing",
            "atrium flower-shaped skylight",
            "non-rectilinear gallery stacks",
        ],
        "scale_hints": {"typical_height_blocks": 30, "typical_width_blocks": 70, "typical_depth_blocks": 45},
    },
    "burj_khalifa": {
        "display_name": "Burj Khalifa (哈利法塔)",
        "architect": "Skidmore, Owings & Merrill",
        "style": "Neo-futurist supertall",
        "distinguishing_features": [
            "stepped setbacks spiraling upward",
            "extreme vertical slenderness",
            "Y-shaped tripartite floor plan",
            "glass and aluminum curtain wall",
            "needle-like spire crown",
        ],
        "scale_hints": {"typical_height_blocks": 160, "typical_width_blocks": 25, "typical_depth_blocks": 25},
    },
    "disney_castle": {
        "display_name": "Disney Cinderella Castle (迪士尼城堡)",
        "style": "Fairytale Romantic Revival",
        "distinguishing_features": [
            "slender pink-blue fairytale spires",
            "central tall keep with steep roofs",
            "symmetrical fantasy castle silhouette",
            "ornate balconies and turrets",
            "not a medieval European fortress layout",
        ],
        "scale_hints": {"typical_height_blocks": 55, "typical_width_blocks": 40, "typical_depth_blocks": 35},
    },
    "fushimi_inari_shrine": {
        "display_name": "Fushimi Inari Taisha (伏见稻荷神社)",
        "style": "Japanese Shinto",
        "distinguishing_features": [
            "thousands of vermillion torii gates in tunnels",
            "mountain path axis through gates",
            "fox statue guardians (kitsune)",
            "main shrine buildings at base",
            "senbon torii corridor rhythm",
        ],
        "scale_hints": {"typical_height_blocks": 12, "typical_width_blocks": 30, "typical_depth_blocks": 80},
    },
    "himeji_castle": {
        "display_name": "Himeji Castle (姬路城)",
        "style": "Japanese feudal",
        "distinguishing_features": [
            "white plastered keep complex (Shirasagi-jo)",
            "tiered curved roof gables",
            "defensive maze-like approach paths",
            "multiple roof tiers and fish ornaments",
            "stone base with wooden superstructure",
        ],
        "scale_hints": {"typical_height_blocks": 45, "typical_width_blocks": 50, "typical_depth_blocks": 50},
    },
    "wuzhen_water_town": {
        "display_name": "Wuzhen (乌镇)",
        "style": "Jiangnan water town",
        "distinguishing_features": [
            "canal streets with stone bridges",
            "whitewashed walls and black-tiled roofs",
            "wooden waterfront verandas",
            "boats along narrow canals",
            "dense historic lane fabric",
        ],
        "scale_hints": {"typical_height_blocks": 10, "typical_width_blocks": 70, "typical_depth_blocks": 90},
    },
    "zhouzhuang_water_town": {
        "display_name": "Zhouzhuang (周庄)",
        "style": "Jiangnan water town",
        "distinguishing_features": [
            "double-bridge canal crossing (Shide/Taishi)",
            "Ming-Qing courtyard houses on water",
            "arched stone bridges",
            "boat-access storefronts",
            "canal-centered town plan",
        ],
        "scale_hints": {"typical_height_blocks": 10, "typical_width_blocks": 65, "typical_depth_blocks": 85},
    },
}


def resolve_research_landmark_id(user_text: str) -> Optional[str]:
    """Return researchOnly archetype id when user names a curated real landmark."""
    text = (user_text or "").strip()
    if not text:
        return None
    try:
        from .archetype_detector import detect_archetype_local
        from .archetype_registry import get_archetype_def

        match = detect_archetype_local(text)
        if match is None or "generic_typology" in match.reason_tags:
            return None
        defn = get_archetype_def(match.id)
        if defn is None or not defn.research_only or defn.id not in RESEARCH_LANDMARK_SEEDS:
            return None
        return defn.id
    except Exception:
        return None


def _merge_unique_strings(existing: List[str], extra: List[str], *, limit: int = 8) -> List[str]:
    out: List[str] = []
    seen: set[str] = set()
    for item in list(existing or []) + list(extra or []):
        s = str(item).strip()
        if not s:
            continue
        key = s.lower()
        if key in seen:
            continue
        seen.add(key)
        out.append(s)
        if len(out) >= limit:
            break
    return out


def apply_research_landmark_seed(
    profile: BuildingProfile,
    user_text: str,
) -> Tuple[BuildingProfile, Optional[str]]:
    """
    Record a non-binding curated reference for researchOnly landmarks.

    Does NOT inject identity, distinguishing_features, scale_hints, skeleton, or
    recommended_components — those are Stage R/P evidence produced by AI, not
    name-bound design commands. Returns (updated_profile, landmark_id or None).
    """
    landmark_id = resolve_research_landmark_id(user_text)
    if not landmark_id:
        return profile, None

    seed = RESEARCH_LANDMARK_SEEDS.get(landmark_id)
    if not seed:
        return profile, landmark_id

    display = str(seed.get("display_name") or landmark_id).strip()
    feature_preview = "; ".join(
        str(f).strip() for f in (seed.get("distinguishing_features") or [])[:5] if f
    )
    notes = (profile.research_notes or "").strip()
    seed_note = (
        f"[LandmarkSeed:{landmark_id}] Curated reference (non-binding): {display}. "
        "Use only as optional research evidence; do not treat as required massing, "
        "component kit, or scale. "
        + (f"Reference traits: {feature_preview}." if feature_preview else "")
    ).strip()
    if seed_note not in notes and f"[LandmarkSeed:{landmark_id}]" not in notes:
        notes = f"{notes}\n{seed_note}".strip() if notes else seed_note

    mc = profile.minecraft_strategy.model_copy()
    mc.landmark_module = None
    # Never write recommended_components / skeleton from seeds.

    return profile.model_copy(
        update={
            "research_notes": notes[:1200] if notes else None,
            "minecraft_strategy": mc,
        }
    ), landmark_id
