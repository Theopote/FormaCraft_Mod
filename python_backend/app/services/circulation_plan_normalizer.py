"""Compile supported legacy stair descriptions to explicit geometry, never a tower-shell preset."""
from __future__ import annotations
from copy import deepcopy
from math import floor, sqrt
from typing import Any


def _integer(value: Any, name: str, minimum: int, maximum: int) -> int:
    if isinstance(value, bool) or int(value) != float(value):
        raise ValueError(f"{name} must be an integer")
    result = int(value)
    if not minimum <= result <= maximum:
        raise ValueError(f"{name} must be {minimum}..{maximum}")
    return result


def _material(value: Any, stairs: bool = False) -> str:
    name = str(value or "stone_bricks")
    name = {"stone_brick": "stone_bricks", "deepslate_brick": "deepslate_bricks"}.get(name, name)
    if stairs:
        name = name.replace("_planks", "_stairs").replace("_bricks", "_brick_stairs")
        if name == "smooth_stone": name = "stone_brick_stairs"
        if not name.endswith("_stairs"): raise ValueError("stair material must have a stair variant")
    return name if ":" in name else "minecraft:" + name


def _point(x: int, y: int, z: int) -> dict:
    return {"x": x, "y": y, "z": z}


def _flight(a: dict, b: dict, width: int, stairs: str, flooring: str) -> dict:
    return {"op": "STAIR_SYSTEM", "from": a, "to": b, "width": width,
            "stairs": stairs, "floor": flooring, "supportMaterial": flooring,
            "clearHeight": 2, "carve": True, "support": True}


def switchback_ops(payload: dict) -> list[dict]:
    width = _integer(payload.get("width", 3), "width", 1, 7)
    rise = _integer(payload.get("flight_rise"), "flight_rise", 1, 32)
    run = _integer(payload.get("flight_run"), "flight_run", rise, 64)
    depth = _integer(payload.get("landing_depth", 2), "landing_depth", 2, 8)
    span = 2 * width + 1
    if payload.get("landing_width", span) != span:
        raise ValueError("switchback landing_width must connect both flight lanes")
    materials = payload.get("material") or {}
    if not isinstance(materials, dict): raise ValueError("switchback material must contain steps and landing")
    stairs = _material(materials.get("steps"), True)
    flooring = _material(materials.get("landing", "oak_planks"))
    left, right = width // 2, width + 1 + width // 2
    # The landing follows the final stair; replacing that stair with a full block creates a jump.
    landing_z = run + 1
    return [
        _flight(_point(0, rise, landing_z + depth // 2),
                _point(span - 1, rise, landing_z + depth // 2), depth, stairs, flooring),
        _flight(_point(left, 0, 0), _point(left, rise, run), width, stairs, flooring),
        _flight(_point(right, rise, landing_z), _point(right, rise * 2, 1), width, stairs, flooring),
        _flight(_point(right, rise * 2, 0), _point(right, rise * 2, 0), width, stairs, flooring),
    ]


def _ring_hints(params: dict, payload: dict) -> dict:
    return params.get("presetParams") or {"shellRadius":payload.get("radius"),"shellHeight":payload.get("height"),
                                         "floorLevels":payload.get("floorLevels"),"floorMaterial":payload.get("floorMaterial","smooth_stone")}


def ring_ops(params: dict, payload: dict) -> list[dict]:
    hints = _ring_hints(params, payload)
    radius = _integer(hints.get("shellRadius"), "shellRadius", 4, 32)
    height = _integer(hints.get("shellHeight"), "shellHeight", 8, 128)
    levels = hints.get("floorLevels")
    if not isinstance(levels, list) or not levels or len(levels) > 16: raise ValueError("ring stairs require 1..16 floorLevels")
    levels = [_integer(y, "floorLevel", 3, height - 3) for y in levels]
    if levels != sorted(set(levels)): raise ValueError("floorLevels must be strictly increasing")
    if any(b-a < 3 for a,b in zip([0]+levels, levels)): raise ValueError("floor levels require at least three blocks of vertical separation")
    if payload.get("direction", "clockwise") not in ("clockwise", "counterclockwise"):
        raise ValueError("ring direction must be clockwise or counterclockwise")
    if payload.get("roof_access") is True: raise ValueError("roof access requires an explicit flight")
    # A square spiral follows face-adjacent grid cells inside the circular wall; no diagonal jumps.
    side = max(1, floor((radius - 2) / sqrt(2)))
    corners = [(-side,-side),(side,-side),(side,side),(-side,side)]
    if str(payload.get("direction", "clockwise")) == "counterclockwise": corners.reverse()
    stairs = _material((hints.get("stair") or {}).get("treadMaterial", "stone_bricks"), True)
    flooring = _material(hints.get("floorMaterial", "smooth_stone"))
    ops = []
    for level in levels:
        ops.extend([{"op":"PUSH_ORIGIN","dy":level},
                    {"op":"CYLINDER","r":radius-1,"h":1,"hollow":False,"material":flooring},
                    {"op":"POP_ORIGIN"}])
    y, edge = 0, 0
    for level in levels:
        while y < level:
            ax, az = corners[edge % 4]; bx, bz = corners[(edge + 1) % 4]
            dx, dz = (bx-ax)//(2*side), (bz-az)//(2*side)
            rise = min(level-y, 2*side-1)
            end = _point(ax + dx*rise, y+rise, az + dz*rise)
            ops.append(_flight(_point(ax,y,az), end, 1, stairs, flooring))
            # Complete the flat landing to the corner without overwriting the final rising tread.
            ops.append(_flight(_point(end['x']+dx,y+rise,end['z']+dz),
                               _point(bx,y+rise,bz), 1, stairs, flooring))
            y += rise; edge += 1
    return ops


def normalize_circulation_plan(plan: dict) -> dict:
    out = deepcopy(plan)
    plates = []
    for comp in out.get("components", []):
        if comp.get("component_type") not in ("ASSEMBLY", "STRUCTURE"): continue
        params = comp.get("params") or {}
        payload = params.get("assembly")
        if not isinstance(payload, dict): continue
        if any(isinstance(op,dict) and op.get('op')=='STAIR_SYSTEM' for op in payload.get('ops', [])):
            continue  # Explicit geometry remains authoritative; validation handles invalid flights.
        try:
            if payload.get("stair_type") == "switchback":
                ops = switchback_ops(payload)
                comp["dimensions"] = {"width": 2*int(payload.get("width",3))+1,
                                      "depth": int(payload["flight_run"])+1+int(payload.get("landing_depth",2)),
                                      "height": 2*int(payload["flight_rise"])+3}
                top = 2*int(payload["flight_rise"])
                for mass in out.get("components", []):
                    if mass.get("component_type") != "MASS_MAIN" or mass.get("slot_id") != comp.get("slot_id"): continue
                    dims, mp = mass["dimensions"], mass.setdefault("params", {})
                    dims["height"] = max(int(dims["height"]), 2*top)
                    mp.update(floor_count=2, floor_height=top, hollow=True)
                    rp = mass.get("relative_position") or _point(0,0,0)
                    corner = mp.get("anchor_mode") in ("min_corner", "corner", "bottom_left")
                    mx = rp['x'] if corner else rp['x']-int(dims['width'])//2
                    mz = rp['z'] if corner else rp['z']-int(dims['depth'])//2
                    size = comp["dimensions"]
                    if size['width'] > int(dims['width'])-2 or size['depth'] > int(dims['depth'])-2:
                        raise ValueError("switchback staircase and landing do not fit inside the hall")
                    old = comp.get("relative_position") or _point(mx+1,rp['y'],mz+1)
                    comp['relative_position'] = _point(max(mx+1,min(old['x'],mx+int(dims['width'])-1-size['width'])),
                                                       rp['y'],max(mz+1,min(old['z'],mz+int(dims['depth'])-1-size['depth'])))
                    plate = {"component_type":"MASS_SECONDARY","relative_position":_point(mx+1,rp['y']+top,mz+1),
                             "dimensions":{"width":int(dims['width'])-2,"depth":int(dims['depth'])-2,"height":1},
                             "features":["floor_plate:circulation"],
                             "params":{"anchor_mode":"min_corner","extrude_mode":"plate","material":"oak_planks"}}
                    if mass.get('slot_id') is not None: plate['slot_id'] = mass['slot_id']
                    plates.append(plate)
                    break
            elif payload.get("kind") == "ring_staircase" or (payload.get("kind") == "spiral_staircase" and params.get("preset") == "spiral_watchtower"):
                ops = ring_ops(params, payload)
                hints = _ring_hints(params, payload)
                r, h = int(hints["shellRadius"]), int(hints["shellHeight"])
                for mass in out.get("components", []):
                    if mass.get("component_type") == "MASS_MAIN" and mass.get("slot_id") == comp.get("slot_id"):
                        mass["dimensions"] = {"width":2*r+1,"depth":2*r+1,"height":h}
                        mass.setdefault("params", {}).update(shape="circle", hollow=True, floor_count=len(hints["floorLevels"])+1)
                        spacing = [b-a for a,b in zip([0]+hints['floorLevels'],hints['floorLevels'])]
                        if len(set(spacing)) == 1: mass['params']['floor_height'] = spacing[0]
                        rp = mass.get('relative_position') or _point(0,0,0)
                        corner = mass['params'].get('anchor_mode') in ('min_corner','corner','bottom_left')
                        comp['relative_position'] = _point(rp['x']+(r if corner else 0),rp['y'],rp['z']+(r if corner else 0))
                comp["dimensions"] = {"width":2*r+1,"depth":2*r+1,"height":h}
            else: continue
            comp["component_type"] = "ASSEMBLY"
            comp["params"] = {"assembly": {"ops": ops}}
        except (ValueError, TypeError, KeyError, OverflowError) as exc:
            out["plan_status"] = "capability_gap"
            out["capability_gap"] = {"code":"E_CIRCULATION_DESCRIPTION_INVALID","message":str(exc),
                                     "path":"components[].params.assembly","suggestions":["Provide explicit flights and adjacent landings."]}
    out.setdefault('components', []).extend(plates)
    return out
