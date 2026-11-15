"""Deterministic dimension defaults and numeric coercion for LlmPlan output.

These are backend repair hints; server compilation still validates geometry.
"""

from typing import Any, Dict, List, Optional

from .llm_plan_wire_normalization import _normalize_component_type


_MASS_COMPONENT_TYPES = {
    "MASS_MAIN",
    "MASS_SECONDARY",
    "MASS_WING",
    "SIDE_WING",
    "MAIN_MASS",
}


_FACADE_COMPONENT_TYPES = {
    "FACADE_WINDOWS",
    "FACADE",
    "WALL_FACADE",
}


_PLANAR_COMPONENT_TYPES = {
    "COURTYARD",
    "COURTYARD_SPACE",
    "PATH",
    "ROAD",
    "PAVING",
    "PLAZA",
    "PLAZA_CORE",
    "TERRACE",
    "TERRACE_PLAZA",
}


_ROOF_COMPONENT_TYPES = {
    "ROOF",
    "ROOF_STRUCTURE",
}


def _coerce_int(value: Any, fallback: Optional[int]) -> Optional[int]:
    try:
        if value is None:
            return fallback
        return int(value)
    except Exception:
        return fallback


def _normalize_vec3(value: Any) -> Dict[str, int]:
    if isinstance(value, dict):
        return {
            "x": _coerce_int(value.get("x"), 0) or 0,
            "y": _coerce_int(value.get("y"), 0) or 0,
            "z": _coerce_int(value.get("z"), 0) or 0,
        }
    if isinstance(value, (list, tuple)) and len(value) >= 3:
        return {
            "x": _coerce_int(value[0], 0) or 0,
            "y": _coerce_int(value[1], 0) or 0,
            "z": _coerce_int(value[2], 0) or 0,
        }
    return {"x": 0, "y": 0, "z": 0}


def _extract_mass_dims(components: List[Any]) -> Optional[Dict[str, int]]:
    for comp in components:
        if not isinstance(comp, dict):
            continue
        ctype = _normalize_component_type(comp.get("component_type"))
        if ctype not in _MASS_COMPONENT_TYPES:
            continue
        dims = comp.get("dimensions")
        if not isinstance(dims, dict):
            continue
        width = _coerce_int(dims.get("width"), None)
        depth = _coerce_int(dims.get("depth"), None)
        height = _coerce_int(dims.get("height"), None)
        if width and width > 0 and depth and depth > 0 and height and height > 0:
            return {"width": width, "depth": depth, "height": height}
    return None


def _param_int(params: Dict[str, Any], *keys: str) -> Optional[int]:
    for key in keys:
        if key in params:
            value = _coerce_int(params.get(key), None)
            if value is not None:
                return value
    return None


def _param_string(params: Dict[str, Any], *keys: str) -> Optional[str]:
    for key in keys:
        if key in params:
            value = params.get(key)
            if value is None:
                continue
            text = str(value).strip()
            if text:
                return text
    return None


def _infer_mass_height(params: Dict[str, Any], fallback: int) -> int:
    floor_height = _param_int(params, "floor_height", "floorHeight")
    floor_count = _param_int(params, "floor_count", "floorCount")
    if floor_height and floor_count:
        return max(3, floor_height * floor_count)
    if floor_height:
        return max(3, floor_height)
    return fallback


def _default_dimensions_for_component(
    ctype: str,
    params: Dict[str, Any],
    mass_dims: Optional[Dict[str, int]],
) -> Dict[str, int]:
    if ctype in _MASS_COMPONENT_TYPES:
        width = mass_dims["width"] if mass_dims else 8
        depth = mass_dims["depth"] if mass_dims else 6
        height = _infer_mass_height(params, mass_dims["height"] if mass_dims else 6)
        return {"width": width, "depth": depth, "height": height}
    if ctype.startswith("TOWER"):
        width = mass_dims["width"] if mass_dims else 6
        depth = mass_dims["depth"] if mass_dims else width
        height = mass_dims["height"] if mass_dims else 12
        return {"width": width, "depth": depth, "height": height}
    if ctype in _ROOF_COMPONENT_TYPES:
        width = mass_dims["width"] if mass_dims else 8
        depth = mass_dims["depth"] if mass_dims else 6
        height = _param_int(params, "roof_height", "roofHeight", "roofHeightBlocks") or 1
        return {"width": width, "depth": depth, "height": max(1, height)}
    if ctype in _FACADE_COMPONENT_TYPES:
        width = mass_dims["width"] if mass_dims else 4
        height = _param_int(params, "floor_height", "floorHeight") or 2
        return {"width": width, "depth": 1, "height": max(1, height)}
    if ctype in ("ENTRANCE", "ENTRANCE_CANOPY", "GATE", "GATE_STRUCTURE"):
        base_width = mass_dims["width"] if mass_dims else 4
        width = max(2, min(base_width, 4))
        height = _param_int(params, "door_height", "doorHeight") or 3
        return {"width": width, "depth": 1, "height": max(2, height)}
    if ctype == "SIGNAGE":
        base_width = mass_dims["width"] if mass_dims else 4
        width = max(2, min(base_width, 6))
        return {"width": width, "depth": 1, "height": 1}
    if ctype in _PLANAR_COMPONENT_TYPES:
        width = mass_dims["width"] if mass_dims else 6
        depth = mass_dims["depth"] if mass_dims else 6
        return {"width": width, "depth": depth, "height": 1}
    width = mass_dims["width"] if mass_dims else 4
    depth = mass_dims["depth"] if mass_dims else 4
    height = mass_dims["height"] if mass_dims else 3
    return {"width": width, "depth": depth, "height": height}


def _normalize_dimensions(
    dims: Any,
    ctype: str,
    params: Dict[str, Any],
    mass_dims: Optional[Dict[str, int]],
) -> Dict[str, int]:
    if not isinstance(dims, dict):
        dims = {}
    defaults = _default_dimensions_for_component(ctype, params, mass_dims)
    width = _coerce_int(dims.get("width"), defaults["width"]) or defaults["width"]
    depth = _coerce_int(dims.get("depth"), defaults["depth"])
    height = _coerce_int(dims.get("height"), defaults["height"])
    if width <= 0:
        width = defaults["width"]
    if depth is None or depth <= 0:
        depth = defaults["depth"]
    if height is None or height <= 0:
        height = defaults["height"]
    if ctype in _FACADE_COMPONENT_TYPES and depth <= 0:
        depth = 1
    if ctype in _PLANAR_COMPONENT_TYPES and height <= 0:
        height = 1
    return {"width": int(width), "depth": int(depth), "height": int(height)}
