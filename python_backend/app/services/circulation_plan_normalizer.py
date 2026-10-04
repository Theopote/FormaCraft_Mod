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


def _mass_origin(mass: dict) -> dict:
    rp = dict(mass.get('relative_position') or _point(0, 0, 0))
    dims, params = mass['dimensions'], mass.get('params') or {}
    if params.get('anchor_mode') not in ('min_corner', 'corner', 'bottom_left'):
        rp['x'] -= int(dims['width']) // 2
        rp['z'] -= int(dims['depth']) // 2
    return rp


def _host_mass(plan: dict, component: dict) -> dict | None:
    masses = [c for c in plan.get('components', []) if c.get('component_type') == 'MASS_MAIN']
    host_id = (component.get('params') or {}).get('host_id')
    if host_id:
        host = next((m for m in masses if (m.get('params') or {}).get('component_id') == host_id), None)
        if host and (plan.get('layout') or {}).get('slots') and host.get('slot_id') != component.get('slot_id'): return None
        return host
    same = [m for m in masses if m.get('slot_id') == component.get('slot_id')]
    if same: masses = same
    elif (plan.get('layout') or {}).get('slots'):
        return None  # Different real slot coordinate systems need an explicit host relation.
    if not masses: return None
    rp = component.get('relative_position') or _point(0, 0, 0)
    def distance(m):
        origin = _mass_origin(m)
        return sum((origin[k] + (int(m['dimensions'][d])-1)/2 - rp[k])**2
                   for k, d in (('x', 'width'), ('z', 'depth')))
    return min(masses, key=distance)


def _fit_straight_stairs(plan: dict) -> None:
    for comp in plan.get('components', []):
        params = comp.get('params') or {}
        if comp.get('component_type') != 'STRUCTURE' or not any(
                f == 'stair:straight_single_run' for f in comp.get('features', [])):
            continue
        if params.get('exterior') is True: continue
        mass = _host_mass(plan, comp)
        if mass is None or not isinstance(params.get('from'), dict) or not isinstance(params.get('to'), dict): continue
        origin, dims = _mass_origin(mass), mass['dimensions']
        wall = max(1, int((mass.get('params') or {}).get('wall_thickness', 1)))
        a, b = params['from'], params['to']
        width = int(params.get('width', 3))
        landing = int(params.get('landing_length', 1))
        rp = comp.get('relative_position') or _point(0, 0, 0)
        found = False
        for rotate in (False, True):
            aa, bb = dict(a), dict(b)
            if rotate:
                aa['x'], aa['z'] = a['z'], a['x']
                bb['x'], bb['z'] = b['z'], b['x']
            dx, dz = bb['x']-aa['x'], bb['z']-aa['z']
            if (dx and dz) or not (dx or dz): continue
            sx, sz = (1 if dx > 0 else -1) if dx else 0, (1 if dz > 0 else -1) if dz else 0
            for length in range(landing, 0, -1):
                extra = length if length > 1 else 0
                ends = [aa, bb, _point(bb['x']+sx*extra, bb['y'], bb['z']+sz*extra)]
                bounds = {}
                for axis, dimension in (('x', 'width'), ('z', 'depth')):
                    lateral = (axis == 'x' and dz != 0) or (axis == 'z' and dx != 0)
                    lo = min(p[axis] for p in ends) - (width//2 if lateral else 0)
                    hi = max(p[axis] for p in ends) + (width-1-width//2 if lateral else 0)
                    low = origin[axis] + wall - lo
                    high = origin[axis] + int(dims[dimension])-1-wall - hi
                    if low > high: break
                    bounds[axis] = max(low, min(rp[axis], high))
                if len(bounds) != 2: continue
                if (mass.get('params') or {}).get('shape') in ('circle', 'cylinder', 'circular', 'round'):
                    cx, cz = origin['x']+(int(dims['width'])-1)/2, origin['z']+(int(dims['depth'])-1)/2
                    rx, rz = int(dims['width'])/2-wall, int(dims['depth'])/2-wall
                    offsets = {}
                    for axis in ('x','z'):
                        lateral = (axis == 'x' and dz != 0) or (axis == 'z' and dx != 0)
                        offsets[axis] = (min(p[axis] for p in ends)-(width//2 if lateral else 0),
                                         max(p[axis] for p in ends)+(width-1-width//2 if lateral else 0))
                    if rx <= 0 or rz <= 0 or any(((bounds['x']+x-cx)/rx)**2 + ((bounds['z']+z-cz)/rz)**2 > 1
                                                 for x in offsets['x'] for z in offsets['z']):
                        continue
                params.update({'from': aa, 'to': bb, 'landing_length': length})
                comp['relative_position'] = _point(bounds['x'], rp['y'], bounds['z'])
                found = True
                break
            if found: break
        if not found:
            plan['plan_status'] = 'capability_gap'
            plan['capability_gap'] = {'code': 'E_STAIR_ENVELOPE_FIT', 'message': 'Interior stair does not fit without cutting exterior walls',
                                      'path': 'components[].params', 'suggestions': ['Use a switchback stair or enlarge the interior.']}


def normalize_circulation_plan(plan: dict) -> dict:
    out = deepcopy(plan)
    _fit_straight_stairs(out)
    plates = []
    for comp in out.get("components", []):
        if comp.get("component_type") not in ("ASSEMBLY", "STRUCTURE"): continue
        params = comp.get("params") or {}
        payload = params.get("assembly")
        if not isinstance(payload, dict): continue
        if any(isinstance(op,dict) and op.get('op')=='STAIR_SYSTEM' for op in payload.get('ops', [])):
            # Canonical ring geometry still needs a host center. Slot labels for the
            # interior and shell may differ even though no layout slots exist.
            if any('ring_stair' in str(f) for f in comp.get('features', [])):
                mass = _host_mass(out, comp)
                if mass is not None:
                    radius = int((mass.get('params') or {}).get('radius', min(int(mass['dimensions']['width']),int(mass['dimensions']['depth']))//2))
                    mass['dimensions'].update(width=2*radius+1, depth=2*radius+1)
                    origin, dims = _mass_origin(mass), mass['dimensions']
                    comp['relative_position'] = _point(origin['x']+int(dims['width'])//2,
                                                       origin['y'], origin['z']+int(dims['depth'])//2)
                    mass.setdefault('params', {})['shape'] = 'circle'
                    wall = max(1, int(mass['params'].get('wall_thickness', 1)))
                    for op in payload['ops']:
                        if op.get('op') == 'CYLINDER' and op.get('h') == 1:
                            op['r'] = min(int(op['r']), min(int(dims['width']),int(dims['depth']))//2-wall)
            continue  # Explicit geometry remains authoritative; validation handles invalid flights.
        try:
            if payload.get("stair_type") == "switchback":
                ops = switchback_ops(payload)
                comp["dimensions"] = {"width": 2*int(payload.get("width",3))+1,
                                      "depth": int(payload["flight_run"])+1+int(payload.get("landing_depth",2)),
                                      "height": 2*int(payload["flight_rise"])+3}
                top = 2*int(payload["flight_rise"])
                for mass in out.get("components", []):
                    if mass is not _host_mass(out, comp): continue
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
                    plate = {"component_type":"MASS_SECONDARY", "slot_id":mass.get("slot_id"), "relative_position":_point(mx+1,rp['y']+top,mz+1),
                             "dimensions":{"width":int(dims['width'])-2,"depth":int(dims['depth'])-2,"height":1},
                             "features":["floor_plate:circulation"],
                             "params":{"anchor_mode":"min_corner","extrude_mode":"plate","material":mp.get("floor_block", "oak_planks"),
                                       **({"host_id":mp["component_id"]} if mp.get("component_id") else {})}}
                    if mass.get('slot_id') is not None: plate['slot_id'] = mass['slot_id']
                    plates.append(plate)
                    break
            elif payload.get("kind") == "ring_staircase" or (payload.get("kind") == "spiral_staircase" and params.get("preset") == "spiral_watchtower"):
                ops = ring_ops(params, payload)
                hints = _ring_hints(params, payload)
                r, h = int(hints["shellRadius"]), int(hints["shellHeight"])
                for mass in out.get("components", []):
                    if mass is _host_mass(out, comp):
                        mass["dimensions"] = {"width":2*r+1,"depth":2*r+1,"height":h}
                        mass.setdefault("params", {}).update(shape="circle", hollow=True, floor_count=len(hints["floorLevels"])+1)
                        spacing = [b-a for a,b in zip([0]+hints['floorLevels'],hints['floorLevels'])]
                        if len(set(spacing)) == 1: mass['params']['floor_height'] = spacing[0]
                        rp = mass.get('relative_position') or _point(0,0,0)
                        corner = mass['params'].get('anchor_mode') in ('min_corner','corner','bottom_left')
                        comp['relative_position'] = _point(rp['x']+(r if corner else 0),rp['y'],rp['z']+(r if corner else 0))
                        wall = max(1, int(mass['params'].get('wall_thickness', 1)))
                        for op in ops:
                            if op.get('op') == 'CYLINDER' and op.get('h') == 1:
                                op['r'] = min(int(op['r']), r-wall)
                comp["dimensions"] = {"width":2*r+1,"depth":2*r+1,"height":h}
            else: continue
            comp["component_type"] = "ASSEMBLY"
            identity = {k: params[k] for k in ("component_id", "host_id", "building_id", "host_source") if k in params}
            comp["params"] = {**identity, "assembly": {"ops": ops}}
        except (ValueError, TypeError, KeyError, OverflowError) as exc:
            out["plan_status"] = "capability_gap"
            out["capability_gap"] = {"code":"E_CIRCULATION_DESCRIPTION_INVALID","message":str(exc),
                                     "path":"components[].params.assembly","suggestions":["Provide explicit flights and adjacent landings."]}
    out.setdefault('components', []).extend(plates)
    return out
