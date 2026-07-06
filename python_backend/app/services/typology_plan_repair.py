"""Repair LlmPlan components: migrated landmark MODULE → STRUCTURE + typology."""

from __future__ import annotations

import logging
from typing import Any, Dict, List, Optional, Tuple

from .typology_registry import get_migration, get_typology

logger = logging.getLogger(__name__)

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

    return plan, count
