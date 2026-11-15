"""Selection bounds and deterministic clipping for planning output.

Selections use inclusive world bounds; positions are relative to the plan anchor.
"""

from typing import Dict, Optional

from ..models.request import BuildRequest


def _selection_dimensions(req: Optional[BuildRequest]) -> Optional[Dict[str, int]]:
    if req is None:
        return None
    sel = req.selection or req.brushSelection
    if sel is None:
        return None
    dx = abs(sel.max.x - sel.min.x) + 1
    dy = abs(sel.max.y - sel.min.y) + 1
    dz = abs(sel.max.z - sel.min.z) + 1
    return {"width": max(1, dx), "height": max(1, dy), "depth": max(1, dz)}


def _clamp_dimensions_to_selection(dims: Dict[str, int], selection_dims: Optional[Dict[str, int]]) -> Dict[str, int]:
    if not selection_dims:
        return dims
    return {
        "width": max(1, min(dims.get("width", 1), selection_dims["width"])),
        "height": max(1, min(dims.get("height", 1), selection_dims["height"])),
        "depth": max(1, min(dims.get("depth", 1), selection_dims["depth"])),
    }


def _selection_bounds_relative(req: Optional[BuildRequest], anchor: Optional[Dict[str, int]]) -> Optional[Dict[str, int]]:
    if req is None:
        return None
    sel = req.selection or req.brushSelection
    if sel is None:
        return None
    if anchor is None:
        anchor = {"x": req.player.pos.x, "y": req.player.pos.y, "z": req.player.pos.z}
    min_x = min(sel.min.x, sel.max.x) - anchor["x"]
    max_x = max(sel.min.x, sel.max.x) - anchor["x"]
    min_y = min(sel.min.y, sel.max.y) - anchor["y"]
    max_y = max(sel.min.y, sel.max.y) - anchor["y"]
    min_z = min(sel.min.z, sel.max.z) - anchor["z"]
    max_z = max(sel.min.z, sel.max.z) - anchor["z"]
    return {"min_x": min_x, "max_x": max_x, "min_y": min_y, "max_y": max_y, "min_z": min_z, "max_z": max_z}


def _clamp_value(value: int, min_value: int, max_value: int) -> int:
    return max(min_value, min(max_value, value))


def _clamp_relative_position(
    rp: Dict[str, int],
    dims: Dict[str, int],
    anchor_mode: str,
    bounds: Optional[Dict[str, int]],
) -> Dict[str, int]:
    if not bounds:
        return rp
    width = max(1, dims.get("width", 1))
    depth = max(1, dims.get("depth", 1))
    height = max(1, dims.get("height", 1))
    if anchor_mode == "min_corner":
        min_x = bounds["min_x"]
        max_x = bounds["max_x"] - (width - 1)
        min_z = bounds["min_z"]
        max_z = bounds["max_z"] - (depth - 1)
    else:
        half_x = width // 2
        half_z = depth // 2
        min_x = bounds["min_x"] + half_x
        max_x = bounds["max_x"] - (width - 1 - half_x)
        min_z = bounds["min_z"] + half_z
        max_z = bounds["max_z"] - (depth - 1 - half_z)
    if max_x < min_x:
        max_x = min_x
    if max_z < min_z:
        max_z = min_z
    min_y = bounds["min_y"]
    max_y = bounds["max_y"] - (height - 1)
    if max_y < min_y:
        max_y = min_y
    rp["x"] = _clamp_value(rp.get("x", 0), min_x, max_x)
    rp["y"] = _clamp_value(rp.get("y", 0), min_y, max_y)
    rp["z"] = _clamp_value(rp.get("z", 0), min_z, max_z)
    return rp
