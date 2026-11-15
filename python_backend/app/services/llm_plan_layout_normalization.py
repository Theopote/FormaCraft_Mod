"""Layout, skeleton and symmetry compatibility normalization for LlmPlan."""

from typing import Any, Dict, List, Optional

from ..models.building_genome import BuildingGenome
from .llm_plan_dimensions import _coerce_int, _normalize_vec3


_VALID_GLOBAL_SYMMETRY = frozenset({"NONE", "MIRROR_X", "MIRROR_Z", "RADIAL"})


_GLOBAL_SYMMETRY_ALIASES: Dict[str, str] = {
    "NONE": "NONE",
    "NO": "NONE",
    "OFF": "NONE",
    "FALSE": "NONE",
    "0": "NONE",
    "X": "MIRROR_X",
    "MIRROR_X": "MIRROR_X",
    "MIRRORX": "MIRROR_X",
    "LEFT_RIGHT": "MIRROR_X",
    "BILATERAL": "MIRROR_X",
    "AXIAL": "MIRROR_X",
    "Z": "MIRROR_Z",
    "MIRROR_Z": "MIRROR_Z",
    "MIRRORZ": "MIRROR_Z",
    "FRONT_BACK": "MIRROR_Z",
    "BOTH": "MIRROR_X",
    "MIRROR_BOTH": "MIRROR_X",
    "FULL": "MIRROR_X",
    "RADIAL": "RADIAL",
    "RADIAL_SYMMETRY": "RADIAL",
    "CIRCULAR": "RADIAL",
    "ROTATIONAL": "RADIAL",
    "GRID": "MIRROR_X",
}


_SKELETON_ALIASES = {
    "TOWER_SHAFT": "VERTICAL_STACK",
    "TOWER": "VERTICAL_STACK",
    "SHAFT": "VERTICAL_STACK",
    "VERTICAL": "VERTICAL_STACK",
    "STACK": "VERTICAL_STACK",
    "TAPER": "VERTICAL_TAPER",
    "LINEAR": "LINEAR_PATH",
    "PATH": "LINEAR_PATH",
    "RADIAL": "RADIAL_RING",
    "RING": "RADIAL_RING",
    "SPOKE": "RADIAL_SPOKE",
    "BRIDGE": "SPAN_SUSPENSION",
    "SUSPENSION": "SPAN_SUSPENSION",
    "COURT": "COURTYARD",
    "YARD": "COURTYARD",
    "LOOP": "PERIMETER_LOOP",
    "WALL": "ENCLOSURE",
    "BAY": "GRID_BAY",
    # Unsupported research shorthand → executable COMPOUND multi-mass composition.
    "CLUSTER": "COMPOUND",
    "CLUSTERED": "COMPOUND",
}


_SKELETON_ALLOWED = {
    "LINEAR_PATH", "RADIAL_RING", "RADIAL_SPOKE", "GRID", "GRID_BAY", "COMPOUND",
    "COURTYARD", "PATH_POLYLINE", "SPAN_SUSPENSION", "VERTICAL_TAPER",
    "VERTICAL_STACK", "PERIMETER_LOOP", "ENCLOSURE",
}


def _symmetry_from_genome_type(genome_type: str) -> Optional[str]:
    t = genome_type.strip().lower()
    if t in ("none", "no", "off"):
        return "NONE"
    if t in ("bilateral", "mirror", "axial"):
        return "MIRROR_X"
    if t in ("radial", "circular", "rotational"):
        return "RADIAL"
    if t == "grid":
        return "MIRROR_X"
    return None


def _normalize_global_symmetry_value(
    value: Any,
    *,
    genome: Optional[Dict[str, Any]] = None,
) -> Optional[str]:
    """Map LLM/genome symmetry aliases to LlmPlan global_constraints.symmetry enum."""
    if value is not None:
        key = str(value).strip().upper().replace("-", "_").replace(" ", "_")
        mapped = _GLOBAL_SYMMETRY_ALIASES.get(key)
        if mapped:
            return mapped
        if key in _VALID_GLOBAL_SYMMETRY:
            return key

    if isinstance(genome, dict):
        sym = genome.get("symmetry")
        if isinstance(sym, dict):
            inferred = _symmetry_from_genome_type(str(sym.get("type") or ""))
            if inferred:
                return inferred
    return None


def _coerce_optional_bool(value: Any) -> Optional[bool]:
    if value is None:
        return None
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)) and value in (0, 1):
        return bool(value)
    if isinstance(value, str):
        s = value.strip().lower()
        if s in ("true", "yes", "1", "on"):
            return True
        if s in ("false", "no", "0", "off"):
            return False
        if "|" in s:
            return None
    return None


def _sanitize_genome_dict(genome: Any) -> Dict[str, Any]:
    if not isinstance(genome, dict):
        return BuildingGenome().model_dump()
    g = dict(genome)
    sym = g.get("symmetry")
    if isinstance(sym, dict):
        sym = dict(sym)
        sym["mirror"] = _coerce_optional_bool(sym.get("mirror"))
        order = sym.get("order")
        if order is not None and not isinstance(order, int):
            sym["order"] = _coerce_int(order, None)
        g["symmetry"] = sym
    return g


def _coerce_skeleton_type(raw: Any) -> Optional[str]:
    if raw is None:
        return None
    text = str(raw).strip().upper().replace("-", "_").replace(" ", "_")
    if not text:
        return None
    if text in _SKELETON_ALLOWED:
        return text
    if text in _SKELETON_ALIASES:
        return _SKELETON_ALIASES[text]
    for alias, canonical in _SKELETON_ALIASES.items():
        if alias in text:
            return canonical
    return "COMPOUND"


def _sanitize_layout_slots(layout: Any) -> Any:
    if not isinstance(layout, dict):
        return layout
    layout = dict(layout)
    if "skeleton_type" in layout:
        coerced = _coerce_skeleton_type(layout.get("skeleton_type"))
        if coerced is None:
            layout.pop("skeleton_type", None)
        else:
            layout["skeleton_type"] = coerced
    slots = layout.get("slots")
    if not isinstance(slots, list):
        return layout
    cleaned: List[Dict[str, Any]] = []
    for i, slot in enumerate(slots):
        if not isinstance(slot, dict):
            continue
        s = dict(slot)
        if s.get("facing") is not None:
            facing = str(s["facing"]).strip().upper()
            facing = {"N": "NORTH", "S": "SOUTH", "E": "EAST", "W": "WEST",
                      "北": "NORTH", "南": "SOUTH", "东": "EAST", "西": "WEST"}.get(facing, facing)
            if facing in ("NORTH", "SOUTH", "EAST", "WEST"):
                s["facing"] = facing
            else:
                # Facing is optional. A courtyard's NONE/ANY must not discard
                # the entire compound in favor of a generic profile building.
                s.pop("facing", None)
        slot_id = str(s.get("slot_id") or "").strip()
        if not slot_id:
            s["slot_id"] = str(s.get("id") or f"slot_{i}").strip()
        anchor = s.get("anchor")
        if not isinstance(anchor, dict):
            s["anchor"] = {"x": 0, "y": 0, "z": 0}
        else:
            s["anchor"] = _normalize_vec3(anchor)
        cp = s.get("component_preset")
        if cp is not None and (not isinstance(cp, str) or not cp.strip()):
            s.pop("component_preset", None)
        cleaned.append(s)
    layout["slots"] = cleaned
    return layout
