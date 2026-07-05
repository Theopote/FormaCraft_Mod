"""
Post-research / post-normalize architectural enrichment for open-world LlmPlan.

Goals: richer facades (pilasters, plinth, cornice), sane footprint/roof ratios,
and proportion_hints when no proportion card matched.
"""
from __future__ import annotations

import re
from typing import Any, Dict, List, Optional, Tuple

from ..models.building_profile import BuildingProfile

_CLASSICAL_MARKERS = (
    "palace", "museum", "cathedral", "church", "temple", "monument", "classical",
    "neoclassical", "renaissance", "baroque", "colonnade", "portico", "pediment",
    "宫", "殿", "庙", "寺", "馆", "卢浮", "louvre", "白宫", "white house",
    "parthenon", "pantheon", "capitol",
)

_MONUMENTAL_MARKERS = _CLASSICAL_MARKERS + (
    "castle", "fortress", "government", "court", "parliament",
    "城堡", "要塞", "市政", "议会",
)


def _text_blob(user_text: str, profile: Optional[BuildingProfile], plan: Dict[str, Any]) -> str:
    parts = [user_text or ""]
    if profile is not None:
        parts.append(profile.query or "")
        parts.append(profile.identity.name or "")
        parts.append(profile.identity.style or "")
        parts.append(" ".join(profile.structure.distinctive_elements or []))
        parts.append(profile.research_notes or "")
    parts.append(str(plan.get("style_profile") or ""))
    return " ".join(parts).lower()


def detect_detail_tier(user_text: str, profile: Optional[BuildingProfile], plan: Dict[str, Any]) -> str:
    blob = _text_blob(user_text, profile, plan)
    if any(m in blob for m in _MONUMENTAL_MARKERS):
        return "monumental"
    if any(m in blob for m in ("house", "cottage", "小屋", "农舍", "villa", "住宅")):
        return "standard"
    return "rich"


def infer_heuristic_proportion_card(
    user_text: str,
    profile: Optional[BuildingProfile] = None,
) -> Dict[str, Any]:
    """Open-world fallback when no proportion_cards JSON matches."""
    blob = _text_blob(user_text, profile, {})
    if any(m in blob for m in _MONUMENTAL_MARKERS):
        return {
            "id": "heuristic_classical_monument",
            "typology": "classical_monument",
            "ratios": {
                "height_to_width": {"min": 0.45, "ideal": 0.62, "max": 0.95},
                "depth_to_width": {"min": 0.85, "ideal": 1.25, "max": 2.0},
                "roof_to_body_height": {"min": 0.18, "ideal": 0.28, "max": 0.42},
                "window_wall_ratio": {"min": 0.1, "ideal": 0.18, "max": 0.3},
            },
            "openingGrammar": {
                "window_aspect": ["vertical_bay", "square"],
                "min_enclosure_coverage": 0.78,
                "max_void_ratio": 0.15,
            },
            "aiInstruction": (
                "Monumental/classical building: use vertical bay windows, pilasters on MASS_MAIN "
                "(params.facade_profile=vertical_pilasters), base plinth, cornice DECOR_DETAIL, "
                "ROOF with overhang and roof_height ≈ 25–35% of body height."
            ),
        }
    return {
        "id": "heuristic_general_building",
        "typology": "general",
        "ratios": {
            "height_to_width": {"min": 0.4, "ideal": 0.65, "max": 1.1},
            "depth_to_width": {"min": 0.75, "ideal": 1.0, "max": 1.45},
            "roof_to_body_height": {"min": 0.15, "ideal": 0.25, "max": 0.4},
            "window_wall_ratio": {"min": 0.12, "ideal": 0.22, "max": 0.35},
        },
        "openingGrammar": {
            "window_aspect": ["square", "horizontal_strip"],
            "min_enclosure_coverage": 0.8,
            "max_void_ratio": 0.18,
        },
        "aiInstruction": (
            "Include FOUNDATION + ROOF + FACADE_WINDOWS + at least one DECOR_DETAIL band; "
            "avoid a bare MASS box."
        ),
    }


def enrich_profile_architectural_detail(
    profile: BuildingProfile,
    user_text: str,
) -> BuildingProfile:
    tier = detect_detail_tier(user_text, profile, {})
    mc = profile.minecraft_strategy.model_copy()
    base = [
        str(c).upper()
        for c in (mc.recommended_components or [])
        if str(c).upper() != "MODULE"
    ]
    extras = ["FOUNDATION", "ROOF", "FACADE_WINDOWS", "ENTRANCE", "DECOR_DETAIL"]
    if tier in ("rich", "monumental"):
        extras.extend(["BALCONY", "TERRACE", "CHIMNEY"])
    merged: List[str] = []
    for c in base + extras:
        if c not in merged:
            merged.append(c)
    mc.recommended_components = merged[:8]

    elements = list(profile.structure.distinctive_elements or [])
    blob = _text_blob(user_text, profile, {})
    for hint, token in (
        ("colonnade", "colonnade"),
        ("pilaster", "pilaster"),
        ("cornice", "cornice"),
        ("portico", "portico"),
        ("柱廊", "colonnade"),
        ("柱", "column"),
        ("装饰", "ornament"),
    ):
        if hint in blob and token not in elements:
            elements.append(token)
    structure = profile.structure.model_copy(update={"distinctive_elements": elements[:12]})

    note = (mc.notes or "").strip()
    richness = (
        f"Detail tier={tier}: require facade_profile on MASS (vertical_pilasters/base_plinth), "
        "wall_pattern gradient/striped, FOUNDATION plinth, ROOF with overhang, "
        "FACADE_WINDOWS with rhythm, ≥1 DECOR_DETAIL cornice/ornament band."
    )
    if richness not in note:
        mc.notes = f"{richness} {note}".strip()

    card = infer_heuristic_proportion_card(user_text, profile)
    scale = profile.scale_hints.model_copy()
    ratios = card.get("ratios") or {}
    hw = ratios.get("height_to_width") or {}
    dw = ratios.get("depth_to_width") or {}
    if scale.typical_width_blocks and not scale.typical_height_blocks:
        ideal = hw.get("ideal", 0.65)
        scale = scale.model_copy(
            update={"typical_height_blocks": max(8, int(scale.typical_width_blocks * ideal))}
        )
    if scale.typical_width_blocks and not scale.typical_depth_blocks:
        ideal_d = dw.get("ideal", 1.0)
        scale = scale.model_copy(
            update={"typical_depth_blocks": max(8, int(scale.typical_width_blocks * ideal_d))}
        )

    return profile.model_copy(
        update={
            "minecraft_strategy": mc,
            "structure": structure,
            "scale_hints": scale,
        }
    )


def _ratio(spec: Any, key: str, default: float) -> float:
    if not isinstance(spec, dict):
        return default
    block = spec.get(key)
    if isinstance(block, dict) and block.get("ideal") is not None:
        try:
            return float(block["ideal"])
        except (TypeError, ValueError):
            pass
    return default


def build_proportion_hints(card: Dict[str, Any], width: int) -> Dict[str, Any]:
    ratios = card.get("ratios") if isinstance(card.get("ratios"), dict) else {}
    hw = _ratio(ratios, "height_to_width", 0.65)
    dw = _ratio(ratios, "depth_to_width", 1.0)
    rh = _ratio(ratios, "roof_to_body_height", 0.25)
    og = card.get("openingGrammar") if isinstance(card.get("openingGrammar"), dict) else {}
    hints: Dict[str, Any] = {
        "typology": card.get("typology") or card.get("id"),
        "target_width": width,
        "target_depth": max(8, int(width * dw)),
        "target_body_height": max(6, int(width * hw)),
        "target_roof_height": max(3, int(width * hw * rh)),
        "height_to_width": hw,
        "depth_to_width": dw,
        "roof_to_body_height": rh,
    }
    wa = og.get("window_aspect")
    if isinstance(wa, list) and wa:
        hints["window_aspect"] = wa[0]
    if og.get("max_void_ratio") is not None:
        hints["max_void_ratio"] = og["max_void_ratio"]
    return hints


def _find_mass(components: List[Dict[str, Any]]) -> Optional[Tuple[int, Dict[str, Any]]]:
    for i, comp in enumerate(components):
        if not isinstance(comp, dict):
            continue
        ctype = str(comp.get("component_type") or "").upper()
        if ctype in ("MASS_MAIN", "MASS_SECONDARY", "MAIN_MASS"):
            return i, comp
    return None


def _has_type(components: List[Dict[str, Any]], ctype: str) -> bool:
    target = ctype.upper()
    return any(
        isinstance(c, dict) and str(c.get("component_type") or "").upper() == target
        for c in components
    )


def _mass_dims(comp: Dict[str, Any]) -> Dict[str, int]:
    dims = comp.get("dimensions") if isinstance(comp.get("dimensions"), dict) else {}
    return {
        "width": max(8, int(dims.get("width") or 24)),
        "depth": max(8, int(dims.get("depth") or 20)),
        "height": max(6, int(dims.get("height") or 14)),
    }


def enrich_llm_plan_architectural_detail(
    plan: Dict[str, Any],
    *,
    user_text: str = "",
    profile: Optional[BuildingProfile] = None,
    proportion_card: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    if not isinstance(plan, dict):
        return plan
    if str(plan.get("plan_status") or "").lower() == "capability_gap":
        return plan
    if str(plan.get("mode") or "").lower() == "patch":
        return plan
    components = plan.get("components")
    if not isinstance(components, list) or not components:
        return plan
    if all(str(c.get("component_type") or "").upper() == "MODULE" for c in components if isinstance(c, dict)):
        return plan

    tier = detect_detail_tier(user_text, profile, plan)

    card = proportion_card or infer_heuristic_proportion_card(user_text, profile)
    mass_hit = _find_mass(components)
    if mass_hit is None:
        return plan
    _, mass = mass_hit
    dims = _mass_dims(mass)
    width = dims["width"]

    hints = build_proportion_hints(card, width)
    existing_hints = plan.get("proportion_hints")
    if not isinstance(existing_hints, dict):
        plan["proportion_hints"] = hints
    else:
        plan["proportion_hints"] = {**hints, **existing_hints}

    body_h = int(hints["target_body_height"])
    depth = int(hints["target_depth"])
    roof_h = int(hints["target_roof_height"])

    mass.setdefault("dimensions", {})
    mass["dimensions"]["width"] = width
    mass["dimensions"]["depth"] = depth
    mass["dimensions"]["height"] = body_h

    params = mass.setdefault("params", {})
    if not isinstance(params, dict):
        params = {}
        mass["params"] = params
    style = str(plan.get("style_profile") or "").upper()
    blob = _text_blob(user_text, profile, plan)
    if tier in ("rich", "monumental") or any(m in blob for m in _CLASSICAL_MARKERS):
        params.setdefault("facade_profile", "vertical_pilasters")
        params.setdefault("wall_pattern", "gradient")
        params.setdefault("floor_height", 4)
        params.setdefault("void_ratio", min(float(params.get("void_ratio") or 0.12), 0.15))
    if "GOTHIC" in style or "CATHEDRAL" in style:
        params["facade_profile"] = "vertical_pilasters"
    features = list(mass.get("features") or [])
    for token in ("pilasters", "base_plinth", "cornice"):
        if token not in [str(f).lower() for f in features]:
            features.append(token)
    mass["features"] = features

    new_components: List[Dict[str, Any]] = list(components)

    if not _has_type(new_components, "FOUNDATION"):
        new_components.insert(
            0,
            {
                "component_type": "FOUNDATION",
                "relative_position": {"x": 0, "y": 0, "z": 0},
                "dimensions": {"width": width + 2, "depth": depth + 2, "height": 2},
                "features": ["plinth", "stylobate"],
                "params": {},
            },
        )

    roof_idx = next(
        (i for i, c in enumerate(new_components) if str(c.get("component_type") or "").upper() == "ROOF"),
        None,
    )
    overhang = 2 if tier == "monumental" else 1
    roof_w = width + overhang * 2
    roof_d = depth + overhang * 2
    roof_spec = {
        "component_type": "ROOF",
        "relative_position": {"x": -overhang, "y": body_h, "z": -overhang},
        "dimensions": {"width": roof_w, "depth": roof_d, "height": roof_h},
        "features": ["overhang", "eaves"],
        "params": {
            "roof_height": roof_h,
            "overhang": overhang,
            "roof_type": params.get("roof_type") or ("hip" if tier == "monumental" else "gable"),
        },
    }
    if roof_idx is None:
        new_components.append(roof_spec)
    else:
        new_components[roof_idx] = {**new_components[roof_idx], **roof_spec}

    if not _has_type(new_components, "FACADE_WINDOWS"):
        new_components.append(
            {
                "component_type": "FACADE_WINDOWS",
                "relative_position": {"x": 0, "y": 1, "z": 0},
                "dimensions": {"width": width, "depth": 1, "height": max(4, body_h - 2)},
                "features": ["facade_rhythm"],
                "params": {
                    "window_aspect": hints.get("window_aspect") or "vertical_bay",
                    "window_ratio": 0.22 if tier == "monumental" else 0.28,
                    "rhythm": "vertical_bay",
                    "rhythm_preset": "CLASSICAL_PILASTER_BAY"
                    if tier in ("rich", "monumental")
                    else "RESIDENTIAL_REGULAR",
                    "reserve_entrance_bay": True,
                },
            },
        )
    else:
        for c in new_components:
            if str(c.get("component_type") or "").upper() == "FACADE_WINDOWS":
                cp = c.setdefault("params", {})
                cp.setdefault("window_aspect", hints.get("window_aspect") or "vertical_bay")
                cp.setdefault("rhythm", "vertical_bay")
                cp.setdefault("window_ratio", 0.25)
                if tier in ("rich", "monumental"):
                    cp.setdefault("rhythm_preset", "CLASSICAL_PILASTER_BAY")

    decor_count = sum(
        1 for c in new_components if str(c.get("component_type") or "").upper() == "DECOR_DETAIL"
    )
    if decor_count < 1 and tier in ("rich", "monumental"):
        new_components.append(
            {
                "component_type": "DECOR_DETAIL",
                "relative_position": {"x": 0, "y": body_h - 1, "z": 0},
                "dimensions": {"width": width, "depth": depth, "height": 2},
                "features": ["cornice", "pattern", "carved"],
                "params": {},
            },
        )
    if decor_count < 2 and tier == "monumental":
        new_components.append(
            {
                "component_type": "DECOR_DETAIL",
                "relative_position": {"x": 0, "y": 0, "z": 0},
                "dimensions": {"width": width, "depth": 1, "height": body_h},
                "features": ["column", "pilaster", "pattern"],
                "params": {"column_spacing": 3},
            },
        )

    if tier == "monumental" and not _has_type(new_components, "ENTRANCE"):
        new_components.append(
            {
                "component_type": "ENTRANCE",
                "relative_position": {"x": max(0, width // 2 - 2), "y": 0, "z": 0},
                "dimensions": {"width": 5, "depth": 2, "height": 5},
                "features": ["portico", "steps"],
                "params": {"door_width": 3, "door_height": 4},
            },
        )

    plan["components"] = new_components
    return plan
