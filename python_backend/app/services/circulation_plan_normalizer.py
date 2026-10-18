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
    if name.startswith('minecraft:'): name = name[len('minecraft:'):]
    name = {"stone_brick": "stone_bricks", "deepslate_brick": "deepslate_bricks"}.get(name, name)
    if stairs:
        name = name.replace("_planks", "_stairs").replace("_bricks", "_brick_stairs")
        if name == "smooth_stone": name = "stone_brick_stairs"
        if name in ('andesite','polished_andesite','diorite','polished_diorite','granite','polished_granite','stone','cobblestone','sandstone','smooth_sandstone','polished_blackstone'):
            name += '_stairs'
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
    from .resolved_geometry import mass_origin
    return mass_origin(mass)


def _host_mass(plan: dict, component: dict) -> dict | None:
    masses = [c for c in plan.get('components', []) if c.get('component_type') in
              ('MASS_MAIN', 'TOWER', 'MASS_WING', 'SIDE_WING', 'MASS_ANCILLARY')]
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


def _stair_door_contact(plan: dict, body: dict, x: int, y: int, z: int, clear: int) -> bool:
    """Only a declared, stair-bound doorway may exempt an exterior shell cell."""
    slots = {s.get('slot_id'): s.get('anchor') or {} for s in (plan.get('layout') or {}).get('slots', [])}
    identity = (body.get('params') or {}).get('component_id')
    for door in plan.get('components', []):
        p = door.get('params') or {}
        if door.get('component_type') != 'ENTRANCE' or p.get('host_id') != identity or not p.get('stair_connection'):
            continue
        rp = door['relative_position']; off = slots.get(door.get('slot_id'), {})
        xx, yy, zz = (rp[a]+off.get(a,0) for a in ('x','y','z'))
        width = int(p.get('door_width') or 2)
        if yy <= y and y+clear < yy+int(p.get('door_height') or 3):
            if p.get('facing') in ('EAST','WEST'):
                if abs(x-xx) <= 1 and zz <= z < zz+width: return True
            elif abs(z-zz) <= 1 and xx <= x < xx+width: return True
    return False


def _bind_exterior_stair_doors(plan: dict) -> None:
    if (plan.get('plan_meta') or {}).get('allow_compiler_satellites') is False: return
    components = plan.get('components', [])
    slots = {s.get('slot_id'): s.get('anchor') or {} for s in (plan.get('layout') or {}).get('slots', [])}
    for stair in components:
        p = stair.get('params') or {}
        if stair.get('component_type') != 'STRUCTURE' or p.get('exterior') is not True: continue
        if p.get('host_source') == 'entrance_elevation': continue
        if p.get('host_id') and p.get('host_source') != 'legacy_geometry_inference': continue
        width, clear = int(p.get('width') or 3), int(p.get('clearHeight') or 2)
        rp = stair['relative_position']; off = slots.get(stair.get('slot_id'), {})
        for endpoint, key in (('from','host_id'),('to','target_host_id')):
            point = p.get(endpoint)
            if not isinstance(point,dict): continue
            end = {a:rp[a]+off.get(a,0)+point[a] for a in ('x','y','z')}
            candidates = []
            for door in components:
                dp = door.get('params') or {}
                if door.get('component_type') != 'ENTRANCE' or not dp.get('host_id'): continue
                pos = door['relative_position']; anchor = slots.get(door.get('slot_id'), {})
                world = {a:pos[a]+anchor.get(a,0) for a in ('x','y','z')}
                if world['y'] != end['y']: continue
                distance = abs(world['x']-end['x'])+abs(world['z']-end['z'])
                if distance <= width+1: candidates.append((distance,door))
            candidates.sort(key=lambda item:item[0])
            if not candidates or len(candidates)>1 and candidates[0][0]==candidates[1][0]: continue
            door = candidates[0][1]; dp = door['params']
            # A real explicit host remains authoritative. Legacy nearest-mass
            # inference used the component origin rather than the raised flight.
            if key == 'host_id' and p.get('host_id') and p.get('host_source') != 'legacy_geometry_inference': continue
            if key == 'target_host_id' and p.get(key) and p[key] != dp['host_id']: continue
            p[key] = dp['host_id']
            if key == 'host_id':
                p['building_id'] = dp['host_id']
            dp['stair_connection'] = p.get('component_id')
            dp['door_width'] = max(width,int(dp.get('door_width') or 2))
            dp['door_height'] = max(clear+1,int(dp.get('door_height') or 3))
        p['host_source'] = 'entrance_elevation'


def _fit_straight_stairs(plan: dict) -> None:
    for comp in plan.get('components', []):
        params = comp.get('params') or {}
        if comp.get('component_type') != 'STRUCTURE' or not any(
                f == 'stair:straight_single_run' for f in comp.get('features', [])):
            continue
        a, b = params.get('from'), params.get('to')
        if not isinstance(a, dict) or not isinstance(b, dict): continue
        # Some plans describe a vertical column rather than an executable flight.
        # Give it the minimum horizontal run while preserving its floor endpoints.
        if a['x'] == b['x'] and a['z'] == b['z'] and a['y'] != b['y']:
            b = dict(b)
            b['z'] = a['z'] + abs(b['y'] - a['y'])
            params['to'] = b
        if params.get('stairs') in ('minecraft:iron_block', 'iron_block'):
            params['stairs'] = 'minecraft:stone_brick_stairs'
        identity = str(params.get('component_id') or '').lower()
        if identity.startswith(('external_stairs', 'exterior_stairs')):
            params['exterior'] = True
        if params.get('exterior') is True: continue
        mass = _host_mass(plan, comp)
        if mass is None or not isinstance(params.get('from'), dict) or not isinstance(params.get('to'), dict): continue
        origin, dims = _mass_origin(mass), mass['dimensions']
        wall = max(1, int((mass.get('params') or {}).get('wall_thickness', 1)))
        a, b = params['from'], params['to']
        width = int(params.get('width', 3))
        landing = int(params.get('landing_length', 1))
        rp = comp.get('relative_position') or _point(0, 0, 0)
        if a['y'] > 0 and a['y'] == rp['y'] - origin['y']:
            # Planner repeated the floor elevation in both component position
            # and local endpoints. Apply that elevation once, not twice.
            aa, bb = dict(a), dict(b)
            aa['y'], bb['y'] = 0, b['y'] - a['y']
            params['from'], params['to'] = a, b = aa, bb
        found = False
        for rotate in (False, True):
            aa, bb = dict(a), dict(b)
            if rotate:
                aa['x'], aa['z'] = a['z'], a['x']
                bb['x'], bb['z'] = b['z'], b['x']
            dx, dz = bb['x']-aa['x'], bb['z']-aa['z']
            if (dx and dz) or not (dx or dz): continue
            sx, sz = (1 if dx > 0 else -1) if dx else 0, (1 if dz > 0 else -1) if dz else 0
            for length in range(max(1, landing), 0, -1):
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
                if params.get('circulation_source') in ('missing_requested_stair','legacy_secondary_stair'):
                    slots={s.get('slot_id'):s.get('anchor') or {} for s in (plan.get('layout') or {}).get('slots',[])}
                    off=slots.get(comp.get('slot_id'),{})
                    ranges={}
                    for axis,dimension in (('x','width'),('z','depth')):
                        lateral=(axis=='x' and dz!=0) or (axis=='z' and dx!=0)
                        lo=min(p[axis] for p in ends)-(width//2 if lateral else 0)
                        hi=max(p[axis] for p in ends)+(width-1-width//2 if lateral else 0)
                        ranges[axis]=(origin[axis]+wall-lo,origin[axis]+int(dims[dimension])-1-wall-hi,lo,hi)
                    candidates=sorted(((x,z) for x in range(ranges['x'][0],ranges['x'][1]+1)
                                       for z in range(ranges['z'][0],ranges['z'][1]+1)),
                                      key=lambda v:abs(v[0]-bounds['x'])+abs(v[1]-bounds['z']))
                    def blocks_foreign_geometry(x,z):
                        for bridge in plan.get('components',[]):
                            bp=bridge.get('params') or {}
                            if bp.get('plate_role') not in ('bridge','link','connector') or params.get('host_id') not in (bp.get('host_id'),bp.get('target_host_id')): continue
                            bo=_mass_origin(bridge); ba=slots.get(bridge.get('slot_id'),{}); bd=bridge['dimensions']
                            bo={a:bo[a]+ba.get(a,0) for a in ('x','y','z')}
                            if not rp['y']+off.get('y',0)+aa['y'] <= bo['y'] <= rp['y']+off.get('y',0)+bb['y']+2: continue
                            if (x+off.get('x',0)+ranges['x'][2] < bo['x']+bd['width'] and x+off.get('x',0)+ranges['x'][3]>=bo['x']
                                    and z+off.get('z',0)+ranges['z'][2] < bo['z']+bd['depth'] and z+off.get('z',0)+ranges['z'][3]>=bo['z']): return True
                        for body in plan.get('components',[]):
                            bp=body.get('params') or {}
                            if body is mass or body.get('component_type') not in ('MASS_MAIN','TOWER','MASS_WING','SIDE_WING','MASS_ANCILLARY'): continue
                            bo=_mass_origin(body); ba=slots.get(body.get('slot_id'),{}); bd=body['dimensions']
                            bo={a:bo[a]+ba.get(a,0) for a in ('x','y','z')}
                            if rp['y']+off.get('y',0)+bb['y']+2 < bo['y'] or rp['y']+off.get('y',0)+aa['y'] >= bo['y']+bd['height']: continue
                            if (x+off.get('x',0)+ranges['x'][2]<bo['x']+bd['width'] and x+off.get('x',0)+ranges['x'][3]>=bo['x']
                                    and z+off.get('z',0)+ranges['z'][2]<bo['z']+bd['depth'] and z+off.get('z',0)+ranges['z'][3]>=bo['z']): return True
                        return False
                    available=next(((x,z) for x,z in candidates if not blocks_foreign_geometry(x,z)),None)
                    if available is None: continue
                    bounds.update(x=available[0],z=available[1])
                if (mass.get('params') or {}).get('shape') in ('circle', 'cylinder', 'circular', 'round'):
                    cx, cz = origin['x']+(int(dims['width'])-1)/2, origin['z']+(int(dims['depth'])-1)/2
                    rx, rz = int(dims['width'])/2-wall, int(dims['depth'])/2-wall
                    offsets = {}
                    for axis in ('x','z'):
                        lateral = (axis == 'x' and dz != 0) or (axis == 'z' and dx != 0)
                        offsets[axis] = (min(p[axis] for p in ends)-(width//2 if lateral else 0),
                                         max(p[axis] for p in ends)+(width-1-width//2 if lateral else 0))
                    for axis, center, dimension in (('x', cx, 'width'), ('z', cz, 'depth')):
                        lo, hi = offsets[axis]
                        lower = origin[axis] + wall - lo
                        upper = origin[axis] + int(dims[dimension])-1-wall - hi
                        bounds[axis] = max(lower, min(round(center-(lo+hi)/2), upper))
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


def _fit_exterior_stairs(plan: dict) -> None:
    """Try a small sideways shift when an exterior flight clips another building."""
    if (plan.get('plan_meta') or {}).get('allow_compiler_satellites') is False:
        return
    slots = {s.get('slot_id'): s.get('anchor') or {} for s in (plan.get('layout') or {}).get('slots', [])}
    bodies = [c for c in plan.get('components', []) if c.get('component_type') in
              ('MASS_MAIN', 'TOWER', 'MASS_WING', 'SIDE_WING', 'MASS_ANCILLARY', 'MASS_SECONDARY', 'FOUNDATION', 'DECOR_DETAIL') and c.get('dimensions')
              and (c.get('component_type') != 'MASS_SECONDARY' or ((c.get('params') or {}).get('extrude_mode') == 'plate'
                   and (c.get('params') or {}).get('plate_role') in ('bridge','link','connector') and c['dimensions']['height']==1))
              and (c.get('component_type') != 'DECOR_DETAIL'
                   or 'cornice' in str((c.get('params') or {}).get('component_id','')).lower()
                   or (c.get('params') or {}).get('decor_type') in ('column','pillar'))]
    def world(c):
        o = _mass_origin(c); off = slots.get(c.get('slot_id'), {})
        cp = c.get('params') or {}
        if c.get('component_type') == 'DECOR_DETAIL' and not cp.get('anchor_mode'):
            o = dict(c['relative_position'])
        if c.get('component_type') == 'FOUNDATION' and 'corner' not in str(cp.get('anchor_mode', '')):
            host = _host_mass(plan,c)
            if host is not None:
                ho = _mass_origin(host)
                for axis in ('x','z'): o[axis] = ho[axis]
        return {a: o[a]+int(off.get(a,0)) for a in ('x','y','z')}
    landings = []
    for comp in plan.get('components', []):
        p = comp.get('params') or {}
        if comp.get('component_type') in ('BALCONY', 'TERRACE'):
            p.setdefault('anchor_mode', 'min_corner')
            if not p.get('host_id') and comp.get('dimensions',{}).get('height') == 1:
                po=world(comp); pd=comp['dimensions']; candidates=[]
                for body in bodies:
                    bp=body.get('params') or {}
                    if body.get('component_type') not in ('MASS_MAIN','TOWER','MASS_WING','SIDE_WING'): continue
                    bo=world(body); bd=body['dimensions']
                    if po['y'] != bo['y']: continue
                    if ((abs(po['z']-(bo['z']+bd['depth'])) <= 1 or abs(po['z']+pd['depth']-bo['z']) <= 1)
                            and po['x'] == bo['x'] and pd['width'] == bd['width']) or (
                            (abs(po['x']-(bo['x']+bd['width'])) <= 1 or abs(po['x']+pd['width']-bo['x']) <= 1)
                            and po['z'] == bo['z'] and pd['depth'] == bd['depth']):
                        candidates.append(bp.get('component_id'))
                if len(candidates)==1 and candidates[0]:
                    p['host_id']=candidates[0]
                    p['circulation_host_source']='matching_ground_facade_deck'
        if comp.get('component_type') == 'TERRACE' and p.get('landing') is True:
            host = _host_mass(plan,comp)
            landing_height = comp.get('dimensions',{}).get('height',0)
            if (host is not None and landing_height > 1
                    and _mass_origin(comp)['y']+landing_height == _mass_origin(host)['y']):
                # Legacy landing boxes encode the rise in their height. The deck
                # belongs at the destination floor, rather than at the box base.
                comp['relative_position']['y'] = _mass_origin(host)['y']
                comp['dimensions']['height'] = 1
                comp['component_type'] = 'MASS_SECONDARY'
                p.update(extrude_mode='plate',plate_role='terrace',anchor_mode='min_corner')
            landings.append(comp)
    for stair in plan.get('components', []):
        p = stair.get('params') or {}
        if stair.get('component_type') != 'STRUCTURE' or p.get('exterior') is not True:
            continue
        a, b = p.get('from'), p.get('to')
        if not isinstance(a,dict) or not isinstance(b,dict): continue
        dx, dz = b['x']-a['x'], b['z']-a['z']
        if bool(dx) == bool(dz): continue
        side = 'x' if dz else 'z'
        off = slots.get(stair.get('slot_id'), {})
        rp = stair.get('relative_position') or _point(0,0,0)
        origin = {axis: rp[axis]+int(off.get(axis,0)) for axis in ('x','y','z')}
        width = int(p.get('width') or 1)
        run = abs(dx or dz)
        landing = int(p.get('landing_length') or 1)
        clear = int(p.get('clearHeight') or 2)
        endpoint = {axis: origin[axis]+b[axis] for axis in ('x','y','z')}
        target_landings = []
        for part in plan.get('components', []):
            pp = part.get('params') or {}
            receiving = part.get('component_type') in ('TERRACE','BALCONY') or (part.get('component_type')=='MASS_SECONDARY'
                        and pp.get('extrude_mode')=='plate' and pp.get('plate_role') in ('platform','partial_platform') and part['dimensions']['height']==1)
            if not receiving or not pp.get('host_id'):
                continue
            po = world(part); pd = part.get('dimensions') or {}
            extent = pd.get('width' if side == 'x' else 'depth',0)
            if (po['y'] == endpoint['y'] and po['x']-width <= endpoint['x'] < po['x']+pd.get('width',0)+width
                    and po['z']-width <= endpoint['z'] < po['z']+pd.get('depth',0)+width):
                target_landings.append(part)
        direct = [c for c in target_landings if endpoint[side]+width-width//2 > world(c)[side]
                  and endpoint[side]-width//2 < world(c)[side]+c['dimensions']['width' if side=='x' else 'depth']]
        if direct: target_landings = direct
        targets = {(c.get('params') or {}).get('host_id') for c in target_landings}
        # Stop the landing inside the receiving deck rather than extending its
        # last rows through the wall behind that deck.
        axis = 'z' if side == 'x' else 'x'
        direction = ((dz>0)-(dz<0)) if side == 'x' else ((dx>0)-(dx<0))
        lengths = []
        for part in target_landings:
            po = world(part); pd = part['dimensions']
            extent = pd['depth' if axis == 'z' else 'width']
            if po[axis] <= endpoint[axis] < po[axis]+extent:
                lengths.append(po[axis]+extent-1-endpoint[axis] if direction > 0 else endpoint[axis]-po[axis])
        if lengths and 1 <= max(lengths) < landing:
            landing = max(lengths)
            p['landing_length'] = landing
        if len(targets) == 1 and p.get('host_id') not in targets and not p.get('target_host_id'):
            p['target_host_id'] = next(iter(targets))
            p['circulation_target_source'] = 'landing_contact'
        # A slowly rising flight must reach deck height at its leading edge;
        # otherwise its last rising rows cut the underside of the receiving deck.
        axis = 'z' if side == 'x' else 'x'
        direction = ((dz>0)-(dz<0)) if side == 'x' else ((dx>0)-(dx<0))
        if len(target_landings)==1 and (target_landings[0].get('params') or {}).get('circulation_host_source')=='matching_ground_facade_deck':
            po=world(target_landings[0]); pd=target_landings[0]['dimensions']
            edge=po[axis] if direction>0 else po[axis]+pd['depth' if axis=='z' else 'width']-1
            overshoot=(endpoint[axis]-edge)*direction
            if 0<overshoot<=landing and run-overshoot>=abs(b['y']-a['y']):
                b=dict(b); b[axis]-=overshoot*direction; p['to']=b
                endpoint[axis]=edge; run-=overshoot
                dx,dz=b['x']-a['x'],b['z']-a['z']
                p['circulation_fit']='receive_at_deck_edge'
        def target_landing_fits(shift, commit=False):
            if not target_landings: return True
            for part in target_landings:
                po = world(part); pd = part['dimensions']
                extent = pd['width' if side == 'x' else 'depth']
                center = endpoint[side]+shift
                extra = max(0,center+width-width//2-(po[side]+extent))
                if center-width//2 < po[side] or extra > min(2,width-1): continue
                axis = 'z' if side == 'x' else 'x'
                length = pd['depth' if side == 'x' else 'width']
                direction = ((dz>0)-(dz<0)) if side == 'x' else ((dx>0)-(dx<0))
                if any(po[axis] <= endpoint[axis]+step*direction < po[axis]+length for step in range(landing+1)):
                    if commit and extra:
                        pd['width' if side == 'x' else 'depth'] += extra
                        pp = part.setdefault('params',{})
                        pp['circulation_fit'] = 'receive_full_stair_width'
                    return True
            return False
        def clips(shift):
            for body in bodies:
                bp = body.get('params') or {}
                own_body = bp.get('component_id') == p.get('host_id')
                if p.get('host_id') and bp.get('host_id') == p.get('host_id') and bp.get('plate_role') not in ('bridge','link','connector'): continue
                bo = world(body); dims = body['dimensions']
                if bp.get('plate_role') in ('bridge','link','connector') and any(
                        c.get('component_type')=='DECOR_DETAIL' and bp.get('component_id') in (
                            (c.get('params') or {}).get('host_id'),(c.get('params') or {}).get('attachment_host_id'))
                        and ((c.get('params') or {}).get('railing_block') or (c.get('params') or {}).get('role')=='railing'
                             or any('railing' in str(f) for f in c.get('features',[]))) for c in plan.get('components',[])):
                    bo=dict(bo); dims=dict(dims)
                    across='x' if dims['depth']>=dims['width'] else 'z'
                    bo[across]-=1; dims['width' if across=='x' else 'depth']+=2
                if body.get('component_type') == 'FOUNDATION' and bo['y']+dims['height'] <= origin['y']+a['y']:
                    continue
                for step in range(run+(landing if landing > 1 else 0)+1):
                    # Only the terminal steps may enter the declared host. The
                    # lower flight still has to clear its own exterior shell.
                    terminal_host = bp.get('component_id') == p.get('target_host_id') or (
                        p.get('target_host_id') is not None and bp.get('host_id') == p.get('target_host_id'))
                    if (own_body or terminal_host) and step >= run-1: continue
                    ratio = min(1,step/max(1,run))
                    point = {axis: origin[axis]+floor(a[axis]+(b[axis]-a[axis])*ratio+0.5) for axis in ('x','y','z')}
                    if step > run:
                        point['x'] += (step-run)*((dx>0)-(dx<0))
                        point['z'] += (step-run)*((dz>0)-(dz<0))
                    point[side] += shift
                    for lateral in range(-(width//2), width-width//2):
                        x, z = point['x']+(lateral if side=='x' else 0), point['z']+(lateral if side=='z' else 0)
                        if (bo['x'] <= x < bo['x']+dims['width'] and bo['z'] <= z < bo['z']+dims['depth']
                                and point['y']-1 < bo['y']+dims['height'] and point['y']+clear >= bo['y']):
                            if _stair_door_contact(plan, body, x, point['y'], z, clear): continue
                            return True
            return False
        if not clips(0) and target_landing_fits(0,True): continue
        rise = abs(b['y']-a['y'])
        if run > rise > 0:
            old_a, old_run = dict(a), run
            # Remove surplus ground-level approach before relocating a flight.
            a = dict(a)
            a['x'] = b['x']-((dx>0)-(dx<0))*rise
            a['z'] = b['z']-((dz>0)-(dz<0))*rise
            run = rise
            if not clips(0):
                if target_landing_fits(0,True):
                    p['from'] = a
                    p['circulation_fit'] = 'shorten_exterior_approach'
                    continue
                # Keep the shorter approach while trying a lateral adjustment.
            # Try the shorter approach together with a lateral adjustment too.
        own_landings = [c for c in landings if (c.get('params') or {}).get('host_id') == p.get('host_id')]
        def reaches_landing(shift):
            if not own_landings: return True
            for landing_body in own_landings:
                lo = world(landing_body); dims = landing_body['dimensions']
                for step in range(landing+1):
                    x = origin['x']+b['x']+step*((dx>0)-(dx<0))+(shift if side=='x' else 0)
                    z = origin['z']+b['z']+step*((dz>0)-(dz<0))+(shift if side=='z' else 0)
                    if lo['x'] <= x < lo['x']+dims['width'] and lo['z'] <= z < lo['z']+dims['depth']:
                        return True
            return False
        for distance in range(1,width+1):
            candidates = [s for s in (distance,-distance) if not clips(s) and reaches_landing(s) and target_landing_fits(s)]
            if candidates:
                target_landing_fits(candidates[0],True)
                p['from'] = a
                stair['relative_position'][side] += candidates[0]
                p['circulation_fit'] = 'avoid_adjacent_body'
                # A partial platform may need up to two extra edge cells to receive
                # the entire shifted stair width. Never enlarge the hall floor.
                for platform in plan.get('components', []):
                    pp = platform.get('params') or {}
                    if pp.get('plate_role') not in ('platform','partial_platform') or pp.get('host_id') != p.get('host_id'):
                        continue
                    po = world(platform); pd = platform['dimensions']
                    end = {axis: origin[axis]+b[axis] for axis in ('x','y','z')}
                    end[side] += candidates[0]
                    axis = 'z' if side == 'x' else 'x'
                    dimension = 'width' if side == 'x' else 'depth'
                    length = 'depth' if side == 'x' else 'width'
                    if not (abs(po['y']-end['y']) <= 1 and po[axis]-landing <= end[axis] <= po[axis]+pd[length]+landing):
                        continue
                    low, high = end[side]-width//2, end[side]+width-width//2
                    if po[side] <= low < po[side]+pd[dimension] and 0 < high-(po[side]+pd[dimension]) <= min(2,width-1):
                        host = _host_mass(plan,stair)
                        ho = world(host) if host is not None else None
                        if ho is not None and high <= ho[side]+host['dimensions'][dimension]:
                            pd[dimension] = high-po[side]
                            pp['circulation_fit'] = 'receive_shifted_stair'
                break
        else:
            # An authored gallery can be approached from the opposite courtyard
            # side when the original bottom runs through an adjacent guest wing.
            if target_landings and rise > 0:
                old_a, old_dx, old_dz, old_run, old_landing = a, dx, dz, run, landing
                a = dict(b)
                a['y'] = old_a['y']
                a['x'] = b['x'] + ((dx>0)-(dx<0))*rise
                a['z'] = b['z'] + ((dz>0)-(dz<0))*rise
                dx, dz, run, landing = b['x']-a['x'], b['z']-a['z'], rise, 1
                for shift in (0, *[s for distance in range(1,width+1) for s in (distance,-distance)]):
                    if clips(shift) or not target_landing_fits(shift): continue
                    target_landing_fits(shift,True)
                    p.update({'from':a, 'landing_length':1, 'circulation_fit':'reverse_gallery_approach'})
                    stair['relative_position'][side] += shift
                    break
                else:
                    a, dx, dz, run, landing = old_a, old_dx, old_dz, old_run, old_landing


def normalize_circulation_plan(plan: dict) -> dict:
    out = deepcopy(plan)
    # Some providers label a fully specified straight stair as a secondary mass.
    # Convert only executable, floor-bound descriptions; ordinary volumes stay volumes.
    for comp in out.get('components', []):
        p = comp.get('params') or {}
        if comp.get('component_type') == 'STRUCTURE' and isinstance(p.get('features'),list):
            comp['features'] = list(dict.fromkeys([*comp.get('features',[]),*p['features']]))
        if (comp.get('component_type') == 'MASS_SECONDARY' and (p.get('shape') == 'stair' or p.get('role') == 'interior_stair' or p.get('extrude_mode') == 'stairs')
                and all(isinstance(p.get(k),int) for k in ('rise','run'))):
            if p.get('extrude_mode')=='stairs':
                p.setdefault('stair_direction',p.get('direction','south'))
                p.setdefault('width',(comp.get('dimensions') or {}).get('width',3))
            p.update(extrude_mode='stair',stair_rise=p['rise'],stair_run=p['run'])
        if comp.get('component_type') == 'STRUCTURE' and any(str(f).startswith('stair:') for f in comp.get('features', [])):
            for key in ('from', 'to'):
                point = p.get(key)
                if isinstance(point, dict):
                    for axis in ('x', 'y', 'z'):
                        value = point.get(axis)
                        if isinstance(value, (int, float)) and not isinstance(value, bool):
                            point[axis] = floor(value + 0.5)
        if comp.get('component_type') == 'MASS_SECONDARY' and p.get('extrude_mode') == 'stair':
            host = _host_mass(out,comp)
            rise, run = p.get('stair_rise'), p.get('stair_run')
            fh = int((host.get('params') or {}).get('floor_height') or 0) if host else 0
            if (host and isinstance(rise,int) and isinstance(run,int) and fh > 0
                    and rise % fh == 0 and 1 <= rise <= run <= 64):
                base = (comp.get('relative_position') or {}).get('y',0)-_mass_origin(host)['y']
                if base >= 0 and base % fh == 0:
                    p.update(stair_type='straight',rise=rise,run=run,from_floor=base//fh+1,
                             to_floor=(base+rise)//fh+1,headroom=p.get('head_clearance',p.get('clearance',2)))
        if comp.get('component_type') != 'MASS_SECONDARY' or p.get('stair_type') != 'straight':
            continue
        host = _host_mass(out, comp)
        if host is None or not all(isinstance(p.get(k), int) for k in ('from_floor', 'to_floor', 'rise', 'run')):
            continue
        rise, run = p['rise'], p['run']
        hp = host.get('params') or {}
        fh = int(hp.get('floor_height') or 0)
        if not (1 <= p['from_floor'] < p['to_floor'] <= int(hp.get('floor_count') or 1)
                and rise == (p['to_floor']-p['from_floor'])*fh and 1 <= rise <= run <= 64):
            continue
        origin = _mass_origin(host)
        rp = comp.get('relative_position') or _point(0,0,0)
        comp['relative_position'] = _point(rp['x'], origin['y']+(p['from_floor']-1)*fh, rp['z'])
        comp['component_type'] = 'STRUCTURE'
        comp['features'] = ['stair:straight_single_run']
        p.update({'anchor_mode': 'min_corner', 'from': _point(0,0,0), 'to': _point(0,rise,run),
                  'stairs': _material(p.get('step_material',p.get('material')), True), 'floor': _material(p.get('step_material',p.get('material'))),
                  'clearHeight': max(2,int(p.get('headroom') or 2)), 'support': False,
                  'landing_length': 1, 'circulation_source': 'legacy_secondary_stair'})
        if p.get('shape') == 'stair' or p.get('stair_direction') in ('north','south','east','west'):
            direction = str(p.get('stair_direction','south')).lower()
            end = {'east':_point(run,rise,0),'west':_point(-run,rise,0),
                   'north':_point(0,rise,-run),'south':_point(0,rise,run)}.get(direction)
            if end is not None: p['to'] = end
            p['landing_length'] = max(1,min(16,int(p.get('landing_depth') or 1)))
            if p.get('shape') == 'stair': p.pop('shape')
    # Older responses inserted a default flight before recognizing the authored
    # secondary stair. Drop only defaults serving the same exact pair of levels.
    explicit_levels = set()
    for c in out.get('components', []):
        cp = c.get('params') or {}
        if (c.get('component_type') != 'STRUCTURE' or 'stair:straight_single_run' not in c.get('features',[])
                or cp.get('circulation_source') == 'missing_requested_stair'
                or not isinstance(cp.get('from'),dict) or not isinstance(cp.get('to'),dict)): continue
        y = c['relative_position']['y']
        explicit_levels.add((cp.get('host_id'),y+cp['from']['y'],y+cp['to']['y']))
    out['components'] = [c for c in out.get('components', []) if not (
        (c.get('params') or {}).get('circulation_source') == 'missing_requested_stair'
        and (c['params'].get('host_id'),c['relative_position']['y']+c['params']['from']['y'],
             c['relative_position']['y']+c['params']['to']['y']) in explicit_levels)]
    from .bridge_plan_normalizer import normalize_hosted_connections
    normalize_hosted_connections(out)
    _complete_requested_stairs(out)
    if (out.get('capability_gap') or {}).get('code') == 'E_STAIR_ENVELOPE_FIT':
        out.pop('capability_gap', None)
        if out.get('plan_status') == 'capability_gap': out.pop('plan_status', None)
    _fit_straight_stairs(out)
    _normalize_bridge_levels(out)
    _bind_exterior_stair_doors(out)
    _fit_exterior_stairs(out)
    _complete_exterior_target_doors(out)
    _complete_stair_door_landings(out)
    # Narrow towers cannot accommodate independently rotated flights whose
    # supports fill the previous flight's headroom. Repeat the fitted stairwell
    # at each floor instead, preserving floor elevations and materials.
    tower_ids = {c.get('params', {}).get('component_id') for c in out.get('components', [])
                 if c.get('component_type') in ('TOWER', 'MASS_MAIN', 'MASS_SECONDARY')
                 and c.get('params', {}).get('component_id')}
    stairwells = {}
    for comp in sorted(out.get('components', []), key=lambda c: (c.get('relative_position') or {}).get('y', 0)):
        params = comp.get('params') or {}
        if params.get('host_id') not in tower_ids or params.get('exterior') is True:
            continue
        if comp.get('component_type') != 'STRUCTURE' or 'stair:straight_single_run' not in comp.get('features', []):
            continue
        a, b = params.get('from'), params.get('to')
        if not isinstance(a, dict) or not isinstance(b, dict): continue
        key = (params['host_id'], b['y']-a['y'], params.get('width', 3))
        first = stairwells.setdefault(key, comp)
        if first is comp: continue
        if comp['relative_position']['y']+a['y'] == first['relative_position']['y']+first['params']['from']['y']: continue
        for axis in ('x', 'z'):
            comp['relative_position'][axis] = first['relative_position'][axis]
            a[axis] = first['params']['from'][axis]
            b[axis] = first['params']['to'][axis]
        params['landing_length'] = first['params'].get('landing_length', 1)
        comp['dimensions']['width'] = first['dimensions']['width']
        comp['dimensions']['depth'] = first['dimensions']['depth']
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


def _complete_exterior_target_doors(plan: dict) -> None:
    """Open a bounded portal where an explicitly connected stair meets a wall."""
    if (plan.get('plan_meta') or {}).get('allow_compiler_satellites') is False: return
    slots = {s.get('slot_id'):s.get('anchor') or {} for s in (plan.get('layout') or {}).get('slots',[])}
    components=plan.get('components',[])
    ids={(c.get('params') or {}).get('component_id'):c for c in components}
    additions=[]
    for c in components:
        p=c.get('params') or {}; target=ids.get(p.get('target_host_id'))
        if c.get('component_type')!='STRUCTURE' or p.get('exterior') is not True or not target: continue
        identity=str(p.get('component_id'))+'#target_door'
        if identity in ids or not isinstance(p.get('to'),dict): continue
        if any(d.get('component_type')=='ENTRANCE' and (d.get('params') or {}).get('stair_connection')==p.get('component_id')
               and d['params'].get('host_id')==p['target_host_id'] for d in components): continue
        off=slots.get(c.get('slot_id'),{}); anchor=slots.get(target.get('slot_id'),{})
        end={a:c['relative_position'][a]+off.get(a,0)+p['to'][a]-anchor.get(a,0) for a in ('x','y','z')}
        ho=_mass_origin(target); hd=target['dimensions']; width=int(p.get('width',3)); clear=int(p.get('clearHeight',2))
        if end['y'] < ho['y'] or end['y']+clear+1 > ho['y']+hd['height']: continue
        choices=[(abs(end['x']-ho['x']),'x',ho['x'],'EAST'),(abs(end['x']-(ho['x']+hd['width']-1)),'x',ho['x']+hd['width']-1,'WEST'),
                 (abs(end['z']-ho['z']),'z',ho['z'],'SOUTH'),(abs(end['z']-(ho['z']+hd['depth']-1)),'z',ho['z']+hd['depth']-1,'NORTH')]
        distance,axis,face,facing=min(choices)
        across='z' if axis=='x' else 'x'; dimension='depth' if axis=='x' else 'width'
        if distance>1 or not ho[across]+1 <= end[across]-width//2 or end[across]+width-width//2 > ho[across]+hd[dimension]-1: continue
        pos=dict(end); pos[axis]=face; pos[across]-=width//2
        additions.append({'component_type':'ENTRANCE','slot_id':target.get('slot_id'),'relative_position':pos,
            'dimensions':{'width':1 if axis=='x' else width,'depth':width if axis=='x' else 1,'height':clear+1},'features':[],
            'params':{'component_id':identity,'host_id':p['target_host_id'],'anchor_mode':'min_corner','facing':facing,
                      'door_width':width,'door_height':clear+1,'entrance_type':'door','stair_connection':p.get('component_id'),
                      'circulation_source':'explicit_stair_wall_contact'}})
    components.extend(additions)


def _normalize_bridge_levels(plan: dict) -> None:
    """Translate legacy one-based storeys only when the authored deck proves it.

    Runtime connection_level is zero-based. Arbitrary height disagreements remain
    validation failures; this does not move bridges to an unrelated upper floor.
    """
    if (plan.get('plan_meta') or {}).get('allow_compiler_satellites') is False: return
    from .resolved_geometry import mass_origin
    slots = {s.get('slot_id'): s.get('anchor') or {} for s in (plan.get('layout') or {}).get('slots', [])}
    components = plan.get('components', [])
    ids = {(c.get('params') or {}).get('component_id'): c for c in components}
    for c in components:
        p = c.get('params') or {}
        level = p.get('connection_level')
        if p.get('plate_role') not in ('bridge', 'link', 'connector') or not isinstance(level, int) or level < 0:
            continue
        host, target = ids.get(p.get('host_id')), ids.get(p.get('target_host_id'))
        if not host or not target: continue
        fh = (host.get('params') or {}).get('floor_height')
        if not isinstance(fh, int) or fh <= 0: continue
        def elevation(part):
            return mass_origin(part)['y'] + slots.get(part.get('slot_id'), {}).get('y', 0)
        deck_y, host_y, target_y = elevation(c), elevation(host), elevation(target)
        tp = target.get('params') or {}
        if (target['dimensions']['height'] == 1 and tp.get('extrude_mode') == 'plate'
                and not tp.get('host_id') and target_y+1 == deck_y):
            target['relative_position']['y'] += 1
            tp['connection_datum_source'] = 'bridge_deck_surface'
            target_y = deck_y
        if (level > 0 and p.get('connection_level_basis') != 'zero_based'
                and deck_y == host_y+(level-1)*fh
                and target_y <= deck_y <= target_y+target['dimensions']['height']):
            p.update(connection_level=level-1, connection_level_basis='zero_based',
                     connection_level_source='authored_deck_one_based_storey')


def _complete_stair_door_landings(plan: dict) -> None:
    """Bridge a small lateral gap between an exterior flight and its named door."""
    if (plan.get('plan_meta') or {}).get('allow_compiler_satellites') is False: return
    components = plan.get('components', [])
    slots = {s.get('slot_id'): s.get('anchor') or {} for s in (plan.get('layout') or {}).get('slots', [])}
    ids = {(c.get('params') or {}).get('component_id') for c in components}
    additions = []
    for stair in components:
        p = stair.get('params') or {}
        if stair.get('component_type') != 'STRUCTURE' or p.get('exterior') is not True: continue
        identity = str(p.get('component_id'))+'#door_landing'
        if identity in ids or not p.get('target_host_id'): continue
        doors = [c for c in components if c.get('component_type') == 'ENTRANCE'
                 and (c.get('params') or {}).get('stair_connection') == p.get('component_id')
                 and c['params'].get('host_id') == p['target_host_id']]
        if len(doors) != 1: continue
        door = doors[0]; dp = door['params']
        host = next((c for c in components if (c.get('params') or {}).get('component_id') == p['target_host_id']),None)
        if host is None: continue
        off = slots.get(stair.get('slot_id'), {})
        end = {a:stair['relative_position'][a]+off.get(a,0)+p['to'][a] for a in ('x','y','z')}
        ho = _mass_origin(host); hd = host['dimensions']; anchor = slots.get(host.get('slot_id'), {})
        ho = {a:ho[a]+anchor.get(a,0) for a in ('x','y','z')}
        side = dp.get('facing') in ('EAST','WEST')
        axis, across = ('x','z') if side else ('z','x')
        face = (ho[axis] if dp.get('facing') in ('EAST','SOUTH')
                else ho[axis]+hd['width' if side else 'depth']-1)
        gap = abs(end[axis]-face)
        if gap <= 1 or gap > int(p.get('width',3))+1: continue
        door_off = slots.get(door.get('slot_id'), {})
        center = door['relative_position'][across]+door_off.get(across,0)
        center += door['dimensions']['depth' if side else 'width']//2
        width = int(p.get('width',3))
        low = {axis:min(end[axis],face), across:min(end[across],center)-width//2, 'y':end['y']}
        high = {axis:max(end[axis],face)+1, across:max(end[across],center)+width-width//2}
        if max(high[a]-low[a] for a in ('x','z')) > width+4: continue
        blocked = False
        for body in components:
            if body is host or body.get('component_type') not in ('MASS_MAIN','MASS_WING','SIDE_WING','TOWER','MASS_ANCILLARY'): continue
            bo = _mass_origin(body); ba = slots.get(body.get('slot_id'), {}); bd = body['dimensions']
            bo = {a:bo[a]+ba.get(a,0) for a in ('x','y','z')}
            if (bo['y'] <= end['y'] < bo['y']+bd['height'] and low['x'] < bo['x']+bd['width']
                    and high['x'] > bo['x'] and low['z'] < bo['z']+bd['depth'] and high['z'] > bo['z']):
                blocked = True; break
        if blocked: continue
        additions.append({'component_type':'MASS_SECONDARY','slot_id':host.get('slot_id'),
                          'relative_position':{a:low[a]-anchor.get(a,0) for a in ('x','y','z')},
                          'dimensions':{'width':high['x']-low['x'],'depth':high['z']-low['z'],'height':1},
                          'features':['stair_door_landing'],
                          'params':{'component_id':identity,'host_id':p['target_host_id'],'building_id':p['target_host_id'],
                                    'extrude_mode':'plate','plate_role':'terrace','anchor_mode':'min_corner',
                                    'material':p.get('floor','minecraft:stone_bricks'),
                                    'circulation_source':'bounded_door_landing'}})
    components.extend(additions)


def _complete_requested_stairs(plan: dict) -> None:
    """Supply a bounded straight stairwell when requested access was entirely omitted."""
    text = (plan.get('proportion_hints', {}).get('architecture_intent', {}).get('original_text') or '')
    meta = plan.get('plan_meta') or {}
    if '楼梯' not in text or meta.get('allow_compiler_satellites') is False:
        return
    if (meta.get('design_requirements') or {}).get('circulation') == 'OMITTED':
        return
    components = plan.setdefault('components', [])
    additions = []
    for mass in components:
        params = mass.get('params') or {}
        if mass.get('component_type') != 'MASS_MAIN' or params.get('extrude_mode') == 'plate':
            continue
        if params.get('shape', 'rectangle') not in ('rectangle', 'rect', 'box'):
            continue
        identity = params.get('component_id')
        count, rise = int(params.get('floor_count') or 1), int(params.get('floor_height') or 0)
        if not identity or not 2 <= count <= 8 or not 3 <= rise <= 8:
            continue
        existing = [c for c in components if (c.get('params') or {}).get('host_id') == identity
                    and (any('stair' in str(f).lower() for f in c.get('features', []))
                         or c.get('component_type') in ('STAIRCASE', 'STAIRS', 'STAIR_SYSTEM', 'ASSEMBLY'))]
        if existing:
            continue
        dims = mass.get('dimensions') or {}
        wall = max(1, int(params.get('wall_thickness') or 1))
        if dims.get('width', 0) < rise + 2 * wall + 3 or dims.get('depth', 0) < 2 * wall + 4:
            continue
        origin = _mass_origin(mass)
        for level in range(count - 1):
            additions.append({'component_type': 'STRUCTURE', 'slot_id': mass.get('slot_id'),
                              'relative_position': _point(origin['x'] + wall + 1, origin['y'] + level * rise, origin['z'] + wall + 1),
                              'dimensions': {'width': rise + 2, 'depth': 2, 'height': rise + 3},
                              'features': ['stair:straight_single_run'],
                              'params': {'component_id': f'{identity}#access_{level + 1}', 'host_id': identity,
                                         'anchor_mode': 'min_corner', 'from': _point(0, 0, 0), 'to': _point(rise, rise, 0),
                                         'width': 2, 'clearHeight': 2, 'landing_length': 1, 'support': False,
                                         'stairs': 'minecraft:stone_brick_stairs', 'floor': params.get('floor_block', 'minecraft:oak_planks'),
                                         'circulation_source': 'missing_requested_stair'}})
    components.extend(additions)
