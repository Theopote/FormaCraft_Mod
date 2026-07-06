"""Repair LlmPlan components: migrated landmark MODULE → STRUCTURE + typology."""

from __future__ import annotations

import logging
from typing import Any, Dict, List, Optional, Tuple

from .typology_registry import get_migration, get_typology

logger = logging.getLogger(__name__)

_TYPOLOGY_EXCLUSIVE_PERIPHERALS = frozenset({"PAVING", "PATH", "COURTYARD_SPACE"})

# Mirrors Java TypologyReferencePresets — applied after reference_landmark merge.
_REFERENCE_PRESETS: Dict[str, Dict[str, Any]] = {
    "famen_pagoda": {
        "footprint": "octagon",
        "levels": 13,
        "height": 47,
        "baseWidth": 10,
        "niche_rhythm": "tier_synced_octagon",
        "detailLevel": "refined",
    },
    "giant_wild_goose_pagoda": {
        "footprint": "square",
        "levels": 7,
        "height": 41,
        "baseWidth": 17,
        "niche_rhythm": "none",
        "detailLevel": "aesthetic",
    },
    "foguang_temple_hall": {
        "baysX": 7,
        "baysZ": 4,
        "width": 21,
        "depth": 15,
        "hallHeight": 7,
        "includeSubEaves": True,
        "roofType": "wudian",
    },
    "temple_of_heaven": {
        "baseRadius": 18,
        "tiers": 3,
        "height": 34,
        "hallRadius": 10,
        "detailLevel": "aesthetic",
        "facing": "SOUTH",
    },
    "birds_nest_stadium": {
        "width": 60,
        "depth": 80,
        "height": 28,
        "elliptical": True,
        "meshStructure": True,
        "bowlSteepness": 0.35,
        "tierStep": 4,
        "facing": "SOUTH",
    },
    "golden_gate_bridge": {
        "span": 180,
        "deckWidth": 9,
        "towerHeight": 44,
        "followTerrain": True,
        "facing": "EAST",
        "detailLevel": "aesthetic",
    },
    "gothic_cathedral": {
        "width": 25,
        "depth": 45,
        "wallHeight": 16,
        "towerHeight": 20,
        "spireHeight": 10,
        "buttressStep": 5,
        "facing": "SOUTH",
    },
    "mingqing_courtyard": {
        "width": 32,
        "depth": 28,
        "includePaths": True,
        "pathWidth": 3,
        "facing": "SOUTH",
    },
    "castle_compound": {
        "width": 48,
        "depth": 36,
        "wallHeight": 6,
        "towerHeight": 18,
        "wallThickness": 2,
        "gateWidth": 3,
        "includePaths": True,
        "pathWidth": 3,
        "moat": True,
        "drawbridge": True,
        "followTerrain": True,
        "facing": "SOUTH",
    },
    "modern_skyscraper": {
        "width": 19,
        "depth": 19,
        "height": 64,
        "floors": 12,
        "setbackEveryFloors": 6,
        "setbackStep": 2,
        "facing": "SOUTH",
    },
    "potala_palace": {
        "baseWidth": 48,
        "baseDepth": 40,
        "tiers": 6,
        "tierInset": 3,
        "tierHeight": 6,
        "platformHeight": 5,
        "facing": "SOUTH",
    },
}


def _extract_landmark_id(comp: Dict[str, Any]) -> Optional[str]:
    features = comp.get("features") or []
    if isinstance(features, list):
        for f in features:
            if not isinstance(f, str):
                continue
            lower = f.strip().lower()
            if lower.startswith("landmark:"):
                return lower.split(":", 1)[1].strip()
            if lower.startswith("module:"):
                return lower.split(":", 1)[1].strip()
    params = comp.get("params") or {}
    if isinstance(params, dict):
        mid = params.get("module_id") or params.get("landmark")
        if mid:
            return str(mid).strip()
    return None


def _has_typology_hint(comp: Dict[str, Any]) -> bool:
    """True when component carries STRUCTURE typology routing (mirrors Java TypologyComponentRouter)."""
    features = comp.get("features") or []
    if isinstance(features, list):
        for f in features:
            if isinstance(f, str) and f.strip().lower().startswith("typology:"):
                return True
    params = comp.get("params") or {}
    if isinstance(params, dict):
        for key in ("typology_id", "structural_typology", "typologyId", "structuralTypologyId"):
            v = params.get(key)
            if v is not None and str(v).strip():
                return True
    return False


def strip_typology_exclusive_compositionals(plan: Dict[str, Any]) -> Tuple[Dict[str, Any], int]:
    """
    When plan already has STRUCTURE + typology:*, drop redundant compositional parts
    (MASS/TOWER/ROOF/…) that the typology builder owns. Mirrors Java applyTypologyExclusiveFilter.
    Returns (plan, stripped_count).
    """
    if not isinstance(plan, dict):
        return plan, 0
    components = plan.get("components")
    if not isinstance(components, list) or not components:
        return plan, 0

    has_typology_structure = any(
        isinstance(c, dict)
        and str(c.get("component_type") or "").upper() == "STRUCTURE"
        and _has_typology_hint(c)
        for c in components
    )
    if not has_typology_structure:
        return plan, 0

    kept: List[Dict[str, Any]] = []
    stripped = 0
    for comp in components:
        if not isinstance(comp, dict):
            continue
        ctype = str(comp.get("component_type") or "").upper()
        if ctype == "STRUCTURE" and _has_typology_hint(comp):
            kept.append(comp)
        elif ctype in _TYPOLOGY_EXCLUSIVE_PERIPHERALS:
            kept.append(comp)
        else:
            stripped += 1

    if stripped:
        plan = dict(plan)
        plan["components"] = kept
        logger.info(
            "TypologyPlanRepair: typology-exclusive stripped %d compositional component(s), kept %d",
            stripped,
            len(kept),
        )
    return plan, stripped


def _typology_params(
    typology_id: str,
    reference_landmark: Optional[str],
    existing_params: Optional[Dict[str, Any]],
) -> Dict[str, Any]:
    params: Dict[str, Any] = {}
    defn = get_typology(typology_id)
    if defn and defn.default_params:
        params.update(defn.default_params)
    if existing_params:
        cleaned = {k: v for k, v in existing_params.items() if k not in ("module_id", "landmark")}
        params.update(cleaned)
    if reference_landmark:
        params.setdefault("reference_landmark", reference_landmark)
        preset = _REFERENCE_PRESETS.get(reference_landmark.strip().lower())
        if preset:
            params.update(preset)
    params["typology_id"] = typology_id
    params["structural_typology"] = typology_id
    return params


def _convert_component(
    comp: Dict[str, Any],
    typology_id: str,
    reference_landmark: str,
) -> Dict[str, Any]:
    dims = comp.get("dimensions") or {"width": 16, "depth": 16, "height": 24}
    params = _typology_params(typology_id, reference_landmark, comp.get("params"))
    features = [
        f
        for f in (comp.get("features") or [])
        if isinstance(f, str)
        and not f.strip().lower().startswith(("landmark:", "module:"))
    ]
    features.append(f"typology:{typology_id}")
    return {
        "component_type": "STRUCTURE",
        "relative_position": comp.get("relative_position") or {"x": 0, "y": 0, "z": 0},
        "dimensions": dims,
        "features": features,
        "params": params,
        "_repaired_from_landmark": reference_landmark,
    }


def repair_migrated_landmark_components(
    plan: Dict[str, Any],
    *,
    structural_typology: Optional[str] = None,
    reference_landmark: Optional[str] = None,
) -> Tuple[Dict[str, Any], int]:
    """
    Convert MODULE/landmark components for typology-first migrated landmarks to STRUCTURE+typology.
    Returns (plan, repair_count).
    """
    if not isinstance(plan, dict):
        return plan, 0
    components = plan.get("components")
    if not isinstance(components, list):
        return plan, 0

    repaired: List[Dict[str, Any]] = []
    count = 0
    for comp in components:
        if not isinstance(comp, dict):
            continue
        landmark_id = _extract_landmark_id(comp)
        ctype = str(comp.get("component_type") or "").upper()

        tid = None
        ref = reference_landmark
        if landmark_id:
            mig = get_migration(landmark_id)
            if mig:
                tid = mig.typology_id
                ref = ref or landmark_id
        elif ctype == "MODULE" and structural_typology:
            tid = structural_typology

        if tid and (ctype == "MODULE" or landmark_id):
            repaired.append(_convert_component(comp, tid, ref or landmark_id or ""))
            count += 1
            logger.info(
                "TypologyPlanRepair: %s → STRUCTURE typology:%s ref=%s",
                landmark_id or ctype,
                tid,
                ref,
            )
            continue
        repaired.append(comp)

    if count:
        plan["components"] = repaired
        tid0 = structural_typology
        if not tid0:
            for c in repaired:
                feats = c.get("features") or []
                for f in feats:
                    if isinstance(f, str) and f.startswith("typology:"):
                        tid0 = f.split(":", 1)[1].strip()
                        break
                if tid0:
                    break
        hints = plan.get("proportion_hints")
        if not isinstance(hints, dict):
            hints = {}
        if tid0:
            hints = dict(hints)
            hints.setdefault("typology", tid0)
            plan["proportion_hints"] = hints
            layout = plan.get("layout")
            if isinstance(layout, dict):
                defn = get_typology(tid0)
                if defn and defn.skeleton_type:
                    layout = dict(layout)
                    layout.setdefault("skeleton_type", defn.skeleton_type)
                    plan["layout"] = layout

    plan, _ = strip_typology_exclusive_compositionals(plan)
    return plan, count
