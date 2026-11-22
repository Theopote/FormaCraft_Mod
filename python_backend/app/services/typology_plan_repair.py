"""Repair LlmPlan components: migrated landmark MODULE → STRUCTURE + typology."""

from __future__ import annotations

import logging
from typing import Any, Dict, List, Optional, Tuple

from ..models.request import BuildRequest

from .typology_registry import get_migration, get_typology, is_compositional_typology

logger = logging.getLogger(__name__)

_TYPOLOGY_EXCLUSIVE_PERIPHERALS = frozenset({"PAVING", "PATH", "COURTYARD_SPACE"})

# Mirrors Java TypologyReferencePresets: compatibility defaults precede authored values.
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


def _param_str(comp: Dict[str, Any], *keys: str) -> Optional[str]:
    params = comp.get("params") if isinstance(comp, dict) else None
    if not isinstance(params, dict):
        return None
    for key in keys:
        v = params.get(key)
        if v is None:
            continue
        s = str(v).strip()
        if s and s.lower() != "null":
            return s
    return None


def _is_whole_building_typology(comp: Dict[str, Any]) -> bool:
    if not isinstance(comp, dict):
        return False
    if str(comp.get("component_type") or "").upper() != "STRUCTURE":
        return False
    if not _has_typology_hint(comp):
        return False
    return _param_str(comp, "host_id", "hostId") is None


def _is_typology_exclusive_building_root(comp: Dict[str, Any]) -> bool:
    ctype = str(comp.get("component_type") or "").upper()
    return (
        ctype.startswith("MASS")
        or ctype in ("STRUCTURE", "MODULE", "TOWER")
        or ctype.startswith("TOWER")
    )


def _typology_host_parent_map(components: List[Any]) -> Dict[str, str]:
    parent_by_id: Dict[str, str] = {}
    for comp in components:
        if not isinstance(comp, dict):
            continue
        cid = _param_str(comp, "component_id", "componentId")
        if not cid:
            continue
        host = _param_str(comp, "host_id", "hostId")
        if host:
            parent_by_id[cid] = host
    return parent_by_id


def _belongs_to_typology_exclusive_scope(
    comp: Dict[str, Any],
    exclusive_ids: set[str],
    exclusive_building_ids: set[str],
    parent_by_id: Dict[str, str],
) -> bool:
    bid = _param_str(comp, "building_id", "buildingId")
    if bid and bid in exclusive_building_ids:
        return True
    cid = _param_str(comp, "component_id", "componentId")
    if cid and cid in exclusive_ids:
        return True
    cursor = _param_str(comp, "host_id", "hostId") or cid
    seen: set[str] = set()
    while cursor and cursor not in seen:
        seen.add(cursor)
        if cursor in exclusive_ids:
            return True
        cursor = parent_by_id.get(cursor)
    return False


def _slot_anchor_map(plan: Optional[Dict[str, Any]]) -> Dict[str, Dict[str, int]]:
    anchors: Dict[str, Dict[str, int]] = {}
    if not isinstance(plan, dict):
        return anchors
    layout = plan.get("layout") or {}
    slots = layout.get("slots") if isinstance(layout, dict) else None
    if not isinstance(slots, list):
        return anchors
    for slot in slots:
        if not isinstance(slot, dict):
            continue
        slot_id = slot.get("slot_id") or slot.get("slotId")
        if not slot_id:
            continue
        anchor = slot.get("anchor") or {}
        if not isinstance(anchor, dict):
            anchor = {}
        try:
            anchors[str(slot_id).strip()] = {
                "x": int(anchor.get("x", 0)),
                "y": int(anchor.get("y", 0)),
                "z": int(anchor.get("z", 0)),
            }
        except (TypeError, ValueError):
            anchors[str(slot_id).strip()] = {"x": 0, "y": 0, "z": 0}
    return anchors


def _authored_slot_id(comp: Dict[str, Any]) -> Optional[str]:
    """Top-level slot_id/slotId, or params.slot_id/slotId (mirrors Java authoredSlotId)."""
    slot_id = comp.get("slot_id") or comp.get("slotId")
    if slot_id is None:
        params = comp.get("params") if isinstance(comp.get("params"), dict) else {}
        slot_id = params.get("slot_id") or params.get("slotId")
    if slot_id is None:
        return None
    text = str(slot_id).strip()
    return text or None


def _footprint_xz(
    comp: Dict[str, Any],
    slot_anchors: Optional[Dict[str, Dict[str, int]]] = None,
) -> Optional[tuple[int, int, int, int]]:
    """
    Plan-space half-open XZ AABB matching Java ComponentFootprintUtil.planSpaceFootprintXz.
    Requires explicit relative_position + dimensions — never invent a 1×1 box at origin.
    Respects anchor_mode / anchorMode and slot.anchor.
    """
    pos = comp.get("relative_position")
    dims = comp.get("dimensions")
    if not isinstance(pos, dict) or not isinstance(dims, dict):
        return None
    if "x" not in pos or "z" not in pos:
        return None
    if "width" not in dims or "depth" not in dims:
        return None
    try:
        x = int(pos["x"])
        z = int(pos["z"])
        w = int(dims["width"])
        d = int(dims["depth"])
    except (TypeError, ValueError, KeyError):
        return None
    if w < 1 or d < 1:
        return None
    params = comp.get("params") if isinstance(comp.get("params"), dict) else {}
    anchor = str(params.get("anchor_mode") or params.get("anchorMode") or "").lower()
    if "corner" in anchor:
        min_x, min_z = x, z
    else:
        min_x, min_z = x - w // 2, z - d // 2
    slot_id = _authored_slot_id(comp)
    if slot_anchors and slot_id and slot_id in slot_anchors:
        min_x += int(slot_anchors[slot_id].get("x", 0))
        min_z += int(slot_anchors[slot_id].get("z", 0))
    return (min_x, min_x + w, min_z, min_z + d)


def _xz_overlaps(a: tuple[int, int, int, int], b: tuple[int, int, int, int]) -> bool:
    return a[0] < b[1] and b[0] < a[1] and a[2] < b[3] and b[2] < a[3]


def _overlaps_any_exclusive_root(
    candidate: Dict[str, Any],
    exclusive_roots: List[Dict[str, Any]],
    slot_anchors: Optional[Dict[str, Dict[str, int]]] = None,
) -> bool:
    cand = _footprint_xz(candidate, slot_anchors)
    if cand is None:
        return False
    for root in exclusive_roots:
        root_box = _footprint_xz(root, slot_anchors)
        if root_box is not None and _xz_overlaps(cand, root_box):
            return True
    return False


def _is_spatially_separated_from_exclusive_roots(
    candidate: Dict[str, Any],
    exclusive_roots: List[Dict[str, Any]],
    slot_anchors: Optional[Dict[str, Dict[str, int]]] = None,
) -> bool:
    """Proven plan-space separation only when comparable against every exclusive root.
    Any root missing geometry → cannot prove independence.
    """
    cand = _footprint_xz(candidate, slot_anchors)
    if cand is None:
        return False
    for root in exclusive_roots:
        root_box = _footprint_xz(root, slot_anchors)
        if root_box is None:
            return False
        if _xz_overlaps(cand, root_box):
            return False
    return True


# Mirrors Java GenerationCompileContext.ComponentOwnership
_OWNERSHIP_EXCLUSIVE = "EXCLUSIVE_HOST"
_OWNERSHIP_INDEPENDENT = "INDEPENDENT_BUILDING"
_OWNERSHIP_AMBIGUOUS = "AMBIGUOUS"
_OWNERSHIP_CONFLICT = "OWNERSHIP_CONFLICT"


def _is_floor_plate(comp: Any) -> bool:
    if not isinstance(comp, dict):
        return False
    params = comp.get("params") if isinstance(comp.get("params"), dict) else {}
    return str(params.get("extrude_mode") or "").lower() == "plate"


def _referenced_host_ids(components: List[Any]) -> set[str]:
    """host_id / target_host_id values that must resolve in the host-ref index."""
    refs: set[str] = set()
    for comp in components:
        if not isinstance(comp, dict):
            continue
        host = _param_str(comp, "host_id", "hostId")
        if host:
            refs.add(host)
        target = _param_str(comp, "target_host_id", "targetHostId")
        if target:
            refs.add(target)
    return refs


def _index_components_by_id(
    components: List[Any],
) -> tuple[Dict[str, Dict[str, Any]], set[str]]:
    """Unique component_id → component; duplicates omitted (no last-writer).

    Floor plates may share display labels and stay out of the host-ref index
    unless their id is referenced by host_id / target_host_id — then they must
    resolve uniquely (e.g. pavilion roof on pavilion_deck).
    """
    by_id: Dict[str, Dict[str, Any]] = {}
    duplicates: set[str] = set()
    referenced = _referenced_host_ids(components)

    def _put(comp: Dict[str, Any]) -> None:
        cid = _param_str(comp, "component_id", "componentId")
        if not cid or cid in duplicates:
            return
        prior = by_id.get(cid)
        if prior is not None and prior is not comp:
            by_id.pop(cid, None)
            duplicates.add(cid)
        else:
            by_id[cid] = comp

    for comp in components:
        if not isinstance(comp, dict) or _is_floor_plate(comp):
            continue
        _put(comp)

    plate_by_id: Dict[str, Dict[str, Any]] = {}
    plate_dupes: set[str] = set()
    for comp in components:
        if not isinstance(comp, dict) or not _is_floor_plate(comp):
            continue
        cid = _param_str(comp, "component_id", "componentId")
        if not cid or cid not in referenced:
            continue
        if cid in plate_dupes or cid in duplicates or cid in by_id:
            by_id.pop(cid, None)
            plate_by_id.pop(cid, None)
            duplicates.add(cid)
            plate_dupes.add(cid)
            continue
        prior = plate_by_id.get(cid)
        if prior is not None and prior is not comp:
            plate_by_id.pop(cid, None)
            duplicates.add(cid)
            plate_dupes.add(cid)
        else:
            plate_by_id[cid] = comp
    for cid, comp in plate_by_id.items():
        if cid not in duplicates:
            by_id[cid] = comp
    return by_id, duplicates


def _component_by_id(components: List[Any]) -> Dict[str, Dict[str, Any]]:
    by_id, _ = _index_components_by_id(components)
    return by_id


def _known_component_ids(components: List[Any]) -> set[str]:
    return set(_component_by_id(components).keys())


def _resolve_base_ownership(
    comp: Dict[str, Any],
    exclusive_roots: List[Dict[str, Any]],
    exclusive_ids: set[str],
    exclusive_building_ids: set[str],
    parent_by_id: Dict[str, str],
    slot_anchors: Optional[Dict[str, Dict[str, int]]] = None,
) -> str:
    """Local ownership without host-id-exists ⇒ independent shortcut."""
    if _belongs_to_typology_exclusive_scope(
        comp, exclusive_ids, exclusive_building_ids, parent_by_id
    ):
        return _OWNERSHIP_EXCLUSIVE
    bid = _param_str(comp, "building_id", "buildingId")
    if bid and (not exclusive_building_ids or bid not in exclusive_building_ids):
        return _OWNERSHIP_INDEPENDENT
    if _overlaps_any_exclusive_root(comp, exclusive_roots, slot_anchors):
        return _OWNERSHIP_EXCLUSIVE
    if _is_spatially_separated_from_exclusive_roots(comp, exclusive_roots, slot_anchors):
        return _OWNERSHIP_INDEPENDENT
    return _OWNERSHIP_AMBIGUOUS


def _building_id_of_exclusive_root(
    exclusive_component_id: str,
    exclusive_roots: List[Dict[str, Any]],
) -> Optional[str]:
    for root in exclusive_roots:
        if _param_str(root, "component_id", "componentId") == exclusive_component_id:
            return _param_str(root, "building_id", "buildingId")
    return None


def _resolve_host_building_id(
    host_comp: Optional[Dict[str, Any]],
    exclusive_roots: List[Dict[str, Any]],
    exclusive_ids: set[str],
    by_id: Dict[str, Dict[str, Any]],
    duplicate_ids: set[str],
) -> Optional[str]:
    """Full host-chain walk (cycle-guarded) for the resolved building_id."""
    if not host_comp:
        return None
    seen: set[str] = set()
    cursor: Optional[Dict[str, Any]] = host_comp
    while cursor is not None:
        bid = _param_str(cursor, "building_id", "buildingId")
        if bid:
            return bid
        cid = _param_str(cursor, "component_id", "componentId")
        if cid and cid in exclusive_ids:
            return _building_id_of_exclusive_root(cid, exclusive_roots)
        if cid and cid in seen:
            return None
        if cid:
            seen.add(cid)
        parent = _param_str(cursor, "host_id", "hostId")
        if not parent or parent in duplicate_ids:
            if parent and parent in exclusive_ids:
                return _building_id_of_exclusive_root(parent, exclusive_roots)
            return None
        if parent in exclusive_ids:
            return _building_id_of_exclusive_root(parent, exclusive_roots)
        cursor = by_id.get(parent)
    return None


def _reconcile_host_inheritance(
    child: Dict[str, Any],
    host_own: str,
    exclusive_building_ids: set[str],
    resolved_host_building_id: Optional[str],
) -> str:
    """Compare child.building_id with resolved host building identity before classifying."""
    child_bid = _param_str(child, "building_id", "buildingId")
    if child_bid and resolved_host_building_id and child_bid != resolved_host_building_id:
        return _OWNERSHIP_CONFLICT
    if child_bid:
        child_exclusive_bid = bool(exclusive_building_ids and child_bid in exclusive_building_ids)
        if child_exclusive_bid and host_own == _OWNERSHIP_INDEPENDENT:
            return _OWNERSHIP_CONFLICT
        if not child_exclusive_bid and host_own == _OWNERSHIP_EXCLUSIVE:
            return _OWNERSHIP_CONFLICT
        if not child_exclusive_bid and host_own == _OWNERSHIP_AMBIGUOUS:
            return _OWNERSHIP_CONFLICT
        if host_own == _OWNERSHIP_CONFLICT:
            return _OWNERSHIP_CONFLICT
    return host_own


def _resolve_effective_ownership(
    comp: Dict[str, Any],
    exclusive_roots: List[Dict[str, Any]],
    exclusive_ids: set[str],
    exclusive_building_ids: set[str],
    parent_by_id: Dict[str, str],
    by_id: Dict[str, Dict[str, Any]],
    duplicate_ids: set[str],
    slot_anchors: Optional[Dict[str, Dict[str, int]]],
    memo: Dict[int, str],
    visiting: set[int],
) -> str:
    key = id(comp)
    cached = memo.get(key)
    if cached is not None:
        return cached
    if key in visiting:
        return _OWNERSHIP_AMBIGUOUS  # host cycle
    visiting.add(key)
    base = _resolve_base_ownership(
        comp, exclusive_roots, exclusive_ids, exclusive_building_ids, parent_by_id, slot_anchors
    )
    host = _param_str(comp, "host_id", "hostId")
    result = base
    if host:
        if host in duplicate_ids:
            result = _OWNERSHIP_AMBIGUOUS
        elif host in exclusive_ids:
            host_bid = _building_id_of_exclusive_root(host, exclusive_roots)
            result = _reconcile_host_inheritance(
                comp, _OWNERSHIP_EXCLUSIVE, exclusive_building_ids, host_bid
            )
        elif host not in by_id:
            result = base
        else:
            host_comp = by_id[host]
            host_own = _resolve_effective_ownership(
                host_comp,
                exclusive_roots,
                exclusive_ids,
                exclusive_building_ids,
                parent_by_id,
                by_id,
                duplicate_ids,
                slot_anchors,
                memo,
                visiting,
            )
            host_bid = _resolve_host_building_id(
                host_comp, exclusive_roots, exclusive_ids, by_id, duplicate_ids
            )
            result = _reconcile_host_inheritance(
                comp, host_own, exclusive_building_ids, host_bid
            )
    visiting.discard(key)
    memo[key] = result
    return result


def _resolve_all_compositional_ownership(
    components: List[Any],
    exclusive_roots: List[Dict[str, Any]],
    exclusive_ids: set[str],
    exclusive_building_ids: set[str],
    parent_by_id: Dict[str, str],
    slot_anchors: Optional[Dict[str, Dict[str, int]]] = None,
) -> Dict[int, str]:
    by_id, duplicate_ids = _index_components_by_id(components)
    memo: Dict[int, str] = {}
    visiting: set[int] = set()
    out: Dict[int, str] = {}
    for comp in components:
        if not isinstance(comp, dict):
            continue
        out[id(comp)] = _resolve_effective_ownership(
            comp,
            exclusive_roots,
            exclusive_ids,
            exclusive_building_ids,
            parent_by_id,
            by_id,
            duplicate_ids,
            slot_anchors,
            memo,
            visiting,
        )
    return out


def _resolve_compositional_ownership(
    comp: Dict[str, Any],
    exclusive_roots: List[Dict[str, Any]],
    exclusive_ids: set[str],
    exclusive_building_ids: set[str],
    parent_by_id: Dict[str, str],
    slot_anchors: Optional[Dict[str, Dict[str, int]]] = None,
    component_by_id: Optional[Dict[str, Dict[str, Any]]] = None,
    duplicate_ids: Optional[set[str]] = None,
) -> str:
    """Building identity first; satellites inherit host-tree ownership (not host-id existence)."""
    by_id = component_by_id if component_by_id is not None else {}
    dups = duplicate_ids if duplicate_ids is not None else set()
    return _resolve_effective_ownership(
        comp,
        exclusive_roots,
        exclusive_ids,
        exclusive_building_ids,
        parent_by_id,
        by_id,
        dups,
        slot_anchors,
        {},
        set(),
    )


def _exclusive_roots_span_distinct_buildings(exclusive_roots: List[Dict[str, Any]]) -> bool:
    if len(exclusive_roots) <= 1:
        return False
    bids: set[str] = set()
    missing = 0
    for root in exclusive_roots:
        bid = _param_str(root, "building_id", "buildingId")
        if not bid:
            missing += 1
        else:
            bids.add(bid)
    if len(bids) > 1:
        return True
    return len(bids) == 1 and missing > 0


def _has_independent_building_beside_typology(
    components: List[Any],
    exclusive_roots: List[Dict[str, Any]],
    exclusive_ids: set[str],
    exclusive_building_ids: set[str],
    slot_anchors: Optional[Dict[str, Dict[str, int]]] = None,
) -> bool:
    """
    Strong evidence only: ownership == INDEPENDENT_BUILDING, or exclusive roots with
    distinct building_id. A different component_id alone is NOT independent.
    """
    if _exclusive_roots_span_distinct_buildings(exclusive_roots):
        return True
    parent_by_id = _typology_host_parent_map(components)
    ownership_by_id = _resolve_all_compositional_ownership(
        components,
        exclusive_roots,
        exclusive_ids,
        exclusive_building_ids,
        parent_by_id,
        slot_anchors,
    )
    exclusive_set = {id(c) for c in exclusive_roots}
    for comp in components:
        if not isinstance(comp, dict) or id(comp) in exclusive_set or _is_whole_building_typology(comp):
            continue
        if _param_str(comp, "host_id", "hostId"):
            continue
        if not _is_typology_exclusive_building_root(comp):
            continue
        if ownership_by_id.get(id(comp)) == _OWNERSHIP_INDEPENDENT:
            return True
    seen_building_ids = set(exclusive_building_ids)
    for comp in components:
        if not isinstance(comp, dict):
            continue
        bid = _param_str(comp, "building_id", "buildingId")
        if not bid:
            continue
        if bid not in seen_building_ids:
            seen_building_ids.add(bid)
            if exclusive_building_ids:
                return True
    return False


def strip_typology_exclusive_compositionals(plan: Dict[str, Any]) -> Tuple[Dict[str, Any], int]:
    """
    When plan already has STRUCTURE + typology:*, drop redundant compositional parts
    (MASS/TOWER/ROOF/…) that the typology builder owns. Mirrors Java applyTypologyExclusiveFilter.

    Single-building: strip anonymous / same-host compositionals plan-wide.
    Mixed compound: EXCLUSIVE_HOST strips; INDEPENDENT keeps; AMBIGUOUS keeps + capability_gap.
    Returns (plan, stripped_count).
    """
    if not isinstance(plan, dict):
        return plan, 0
    components = plan.get("components")
    if not isinstance(components, list) or not components:
        return plan, 0

    exclusive_roots = [c for c in components if isinstance(c, dict) and _is_whole_building_typology(c)]
    if not exclusive_roots:
        return plan, 0

    exclusive_ids: set[str] = set()
    exclusive_building_ids: set[str] = set()
    for root in exclusive_roots:
        cid = _param_str(root, "component_id", "componentId")
        if cid:
            exclusive_ids.add(cid)
        bid = _param_str(root, "building_id", "buildingId")
        if bid:
            exclusive_building_ids.add(bid)

    parent_by_id = _typology_host_parent_map(components)
    slot_anchors = _slot_anchor_map(plan)
    multi_building = _has_independent_building_beside_typology(
        components, exclusive_roots, exclusive_ids, exclusive_building_ids, slot_anchors
    )
    ownership_by_id = _resolve_all_compositional_ownership(
        components,
        exclusive_roots,
        exclusive_ids,
        exclusive_building_ids,
        parent_by_id,
        slot_anchors,
    )
    _, duplicate_ids = _index_components_by_id(components)

    kept: List[Dict[str, Any]] = []
    stripped = 0
    ambiguous_ids: List[str] = []
    conflict_ids: List[str] = []
    for comp in components:
        if not isinstance(comp, dict):
            continue
        ctype = str(comp.get("component_type") or "").upper()
        hosted = _param_str(comp, "host_id", "hostId") is not None
        # Unhosted typology STRUCTURE / site peripherals keep; hosted ones obey ownership.
        if ctype == "STRUCTURE" and _has_typology_hint(comp) and not hosted:
            kept.append(comp)
            continue
        if ctype in _TYPOLOGY_EXCLUSIVE_PERIPHERALS and not hosted:
            kept.append(comp)
            continue
        ownership = ownership_by_id.get(id(comp), _OWNERSHIP_AMBIGUOUS)
        if ownership == _OWNERSHIP_EXCLUSIVE:
            stripped += 1
        else:
            kept.append(comp)
            cid = _param_str(comp, "component_id", "componentId") or ctype
            if ownership == _OWNERSHIP_CONFLICT:
                conflict_ids.append(cid)
            elif ownership == _OWNERSHIP_AMBIGUOUS:
                ambiguous_ids.append(cid)

    changed = stripped > 0 or bool(ambiguous_ids) or bool(conflict_ids) or bool(duplicate_ids)
    if changed:
        plan = dict(plan)
        plan["components"] = kept
    if duplicate_ids and not plan.get("capability_gap"):
        plan = dict(plan)
        plan["plan_status"] = "capability_gap"
        plan["capability_gap"] = {
            "code": "E_DUPLICATE_COMPONENT_ID",
            "message": (
                "存在重复的 params.component_id，宿主引用无法唯一解析；"
                "请为每个可被 host_id 引用的构件分配唯一 ID。"
            ),
            "path": "components[].params.component_id",
            "suggestions": [
                "Give every hostable / referenceable component a unique component_id.",
                "Do not rely on list order to disambiguate duplicate ids.",
            ],
            "details": {"duplicate_component_ids": sorted(duplicate_ids)},
        }
        logger.info(
            "TypologyPlanRepair: duplicate component_id(s) for host resolution: %s",
            sorted(duplicate_ids),
        )
    elif conflict_ids and not plan.get("capability_gap"):
        plan = dict(plan)
        plan["plan_status"] = "capability_gap"
        plan["capability_gap"] = {
            "code": "E_COMPONENT_OWNERSHIP_CONFLICT",
            "message": (
                "构件的 building_id 与 host_id 宿主链建筑身份冲突；"
                "请修正绑定或改用显式连接关系，勿静默删除。"
            ),
            "path": "components",
            "suggestions": [
                "Align building_id with the host building, or clear the mistaken host_id.",
                "Cross-building links need an explicit connection, not a conflicting host_id.",
                "Do not strip conflicted independent buildings silently.",
            ],
            "details": {
                "conflict_component_ids": list(conflict_ids),
                "exclusive_host_ids": sorted(exclusive_ids),
            },
        }
        logger.info(
            "TypologyPlanRepair: typology-exclusive ownership conflict for %d component(s): %s",
            len(conflict_ids),
            conflict_ids,
        )
    elif ambiguous_ids and not plan.get("capability_gap"):
        plan = dict(plan)
        plan["plan_status"] = "capability_gap"
        plan["capability_gap"] = {
            "code": "E_COMPONENT_OWNERSHIP_AMBIGUOUS",
            "message": (
                "存在归属不明的构件（缺少可判定的 building_id / 有效 host_id / 几何），"
                "无法安全确认是否为独占执行器的重复体量；请补全身份与尺寸后重试。"
            ),
            "path": "components",
            "suggestions": [
                "Assign distinct building_id for independent buildings.",
                "Bind satellites with a resolvable host_id, or provide relative_position + dimensions.",
                "Do not drop ambiguous components silently.",
            ],
            "details": {
                "ambiguous_component_ids": list(ambiguous_ids),
                "exclusive_host_ids": sorted(exclusive_ids),
            },
        }
        logger.info(
            "TypologyPlanRepair: typology-exclusive ownership ambiguous for %d component(s): %s",
            len(ambiguous_ids),
            ambiguous_ids,
        )
    if stripped:
        logger.info(
            "TypologyPlanRepair: typology-exclusive stripped %d compositional component(s), kept %d (multi_building=%s)",
            stripped,
            len(kept),
            multi_building,
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
    if reference_landmark:
        params.setdefault("reference_landmark", reference_landmark)
        preset = _REFERENCE_PRESETS.get(reference_landmark.strip().lower())
        if preset:
            params.update(preset)
    if existing_params:
        cleaned = {k: v for k, v in existing_params.items() if k not in ("module_id", "landmark")}
        params.update(cleaned)
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
        **({"slot_id": comp["slot_id"]} if "slot_id" in comp else {}),
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

        if tid and not is_compositional_typology(tid) and (ctype == "MODULE" or landmark_id):
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


def _extract_landmark_module_id(comp: Dict[str, Any]) -> Optional[str]:
    for feature in comp.get("features") or []:
        if not isinstance(feature, str):
            continue
        f = feature.strip().lower()
        if f.startswith("landmark:"):
            return f.split(":", 1)[1].strip()
        if f.startswith("module:"):
            return f.split(":", 1)[1].strip()
    params = comp.get("params")
    if isinstance(params, dict):
        module_id = params.get("module_id")
        if isinstance(module_id, str) and module_id.strip():
            return module_id.strip().lower()
    return None


def _sanitize_landmark_modules(
    plan: Dict[str, Any],
    req: Optional[BuildRequest],
    building_profile: Optional[Any] = None,
) -> None:
    if getattr(getattr(building_profile, "minecraft_strategy", None), "model_designed_landmark", False):
        return
    stid: Optional[str] = None
    ref_lm: Optional[str] = None
    if building_profile is not None:
        mc = getattr(building_profile, "minecraft_strategy", None)
        if mc is not None:
            raw_st = getattr(mc, "structural_typology", None)
            raw_ref = getattr(mc, "reference_landmark", None)
            stid = str(raw_st).strip() if raw_st else None
            ref_lm = str(raw_ref).strip() if raw_ref else None

    plan, _ = repair_migrated_landmark_components(
        plan,
        structural_typology=stid,
        reference_landmark=ref_lm,
    )

    components = plan.get("components")
    if not isinstance(components, list):
        return

    from .typology_registry import is_migrated_landmark

    def _strip_remaining_migrated_modules(comps: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        kept_local: List[Dict[str, Any]] = []
        for comp in comps:
            if not isinstance(comp, dict):
                continue
            actual = _extract_landmark_module_id(comp)
            ctype = str(comp.get("component_type") or "").upper()
            if (ctype == "MODULE" or actual) and actual and is_migrated_landmark(actual):
                continue
            kept_local.append(comp)
        return kept_local

    components = _strip_remaining_migrated_modules(components)
    plan["components"] = components

    expected_id: Optional[str] = None
    profile_authoritative = building_profile is not None

    if building_profile is not None:
        mc = getattr(building_profile, "minecraft_strategy", None)
        lm = getattr(mc, "landmark_module", None) if mc is not None else None
        expected_id = str(lm).strip().lower() if lm else None
    elif req is not None:
        raw_msg = getattr(req, "userMessage", None)
        user_text = raw_msg.strip() if isinstance(raw_msg, str) else ""
        if user_text:
            from .building_research_agent import resolve_landmark_module_for_intent

            expected_id = resolve_landmark_module_for_intent(user_text)

    if expected_id and is_migrated_landmark(expected_id):
        expected_id = None

    if profile_authoritative and not expected_id:
        filtered = []
        for comp in components:
            if not isinstance(comp, dict):
                continue
            ctype = str(comp.get("component_type") or "").upper()
            if ctype == "MODULE" or _extract_landmark_module_id(comp):
                continue
            filtered.append(comp)
        plan["components"] = filtered
        return

    if not expected_id:
        return

    kept: List[Dict[str, Any]] = []
    module_seen = False
    for comp in components:
        if not isinstance(comp, dict):
            continue
        actual = _extract_landmark_module_id(comp)
        ctype = str(comp.get("component_type") or "").upper()
        if ctype == "MODULE" or actual:
            if actual and actual != expected_id:
                continue
            if module_seen:
                continue
            features = [
                f for f in (comp.get("features") or [])
                if isinstance(f, str)
                and not f.strip().lower().startswith(("landmark:", "module:"))
            ]
            params = dict(comp.get("params") or {})
            params["module_id"] = expected_id
            comp = dict(comp)
            comp["component_type"] = "MODULE"
            comp["features"] = list(features) + [f"landmark:{expected_id}"]
            comp["params"] = params
            module_seen = True
        kept.append(comp)
    if not module_seen:
        kept.insert(
            0,
            {
                "component_type": "MODULE",
                "relative_position": {"x": 0, "y": 0, "z": 0},
                "dimensions": {"width": 32, "depth": 24, "height": 16},
                "features": [f"landmark:{expected_id}"],
                "params": {"module_id": expected_id},
            },
        )
    plan["components"] = kept
