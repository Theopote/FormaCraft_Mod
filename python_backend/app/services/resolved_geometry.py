"""Geometry shared by plan contracts and circulation; coordinates match the components route."""
from __future__ import annotations
import re


def _integer(value, default=0):
    try:
        return default if isinstance(value, bool) else int(value)
    except (ValueError, TypeError, OverflowError):
        return default


def explicit_floor_height(component: dict) -> int:
    params = component.get('params') or {}
    height = _integer(params.get('floor_height') or params.get('floorHeight'))
    if height > 0:
        return height
    for feature in component.get('features') or []:
        match = re.search(r'(?:floor[_-]?height|层高|每层)[:=]?\s*(\d+)\s*(?:米|m|格)?', str(feature).lower())
        if match:
            return _integer(match[1])
    return 0


def body_dimensions(component: dict) -> dict:
    dimensions = dict(component.get('dimensions') or {})
    if component.get('component_type') in ('MASS_MAIN', 'MAIN_MASS') and (component.get('params') or {}).get('extrude_mode') != 'plate':
        dimensions['width'] = max(3, _integer(dimensions.get('width'), 1))
        dimensions['depth'] = max(3, _integer(dimensions.get('depth'), 1))
        dimensions['height'] = max(3, _integer(dimensions.get('height'), 1), explicit_floor_height(component))
    return dimensions


def mass_origin(component: dict) -> dict:
    position = dict(component.get('relative_position') or {'x': 0, 'y': 0, 'z': 0})
    dimensions, params = body_dimensions(component), component.get('params') or {}
    anchor = str(params.get('anchor_mode') or params.get('anchorMode') or '').lower()
    if 'corner' not in anchor:
        position['x'] -= max(1, int(dimensions.get('width', 1))) // 2
        position['z'] -= max(1, int(dimensions.get('depth', 1))) // 2
    return position


def mass_center(component: dict) -> tuple[float, float]:
    origin, dimensions = mass_origin(component), body_dimensions(component)
    return (origin['x'] + (int(dimensions.get('width', 1))-1)/2,
            origin['z'] + (int(dimensions.get('depth', 1))-1)/2)


def resolve_buildings(plan: dict) -> list[dict]:
    slots = {s.get('slot_id'): s for s in (plan.get('layout') or {}).get('slots', []) if isinstance(s, dict)}
    result = []
    for component in plan.get('components') or []:
        if component.get('component_type') != 'MASS_MAIN':
            continue
        params, dimensions = component.get('params') or {}, body_dimensions(component)
        if params.get('extrude_mode') == 'plate':
            continue
        origin = mass_origin(component)
        slot_anchor = slots.get(component.get('slot_id'), {}).get('anchor') or {}
        plan_origin = {axis: origin[axis] + int(slot_anchor.get(axis, 0)) for axis in ('x', 'y', 'z')}
        height = explicit_floor_height(component)
        count = _integer(params.get('floor_count') or params.get('floorCount'))
        envelope_height = int(dimensions.get('height', 1))
        fitting = min(count, (max(1, envelope_height)-1)//height+1) if count > 0 and height > 0 else 0
        result.append({'component_id': params.get('component_id'), 'slot_id': component.get('slot_id'),
                       'local_origin': origin, 'plan_origin': plan_origin, 'dimensions': dict(dimensions),
                       'floor_ys_local': [origin['y'] + n*height for n in range(fitting)],
                       'roof_y_local': origin['y'] + envelope_height-1,
                       'floor_layout_fits': count <= 0 or height <= 0 or count*height <= envelope_height,
                       'coordinate_stage': 'plan', 'geometry_status': 'parameters_only',
                       'multi_mass': multi_mass_geometry(component)})
    return result


def resolve_mass_parts(component: dict) -> list[dict]:
    origin, dimensions = mass_origin(component), body_dimensions(component)
    params = component.get('params') or {}
    identity = params.get('component_id') or f"body@{origin['x']}:{origin['y']}:{origin['z']}"
    parts = [{'part_id': identity, 'local_origin': origin, 'dimensions': dimensions, 'source_params': params}]
    items = params.get('masses')
    if not isinstance(items, list):
        return parts
    for index, item in enumerate(items):
        if not isinstance(item, dict) or not isinstance(item.get('dimensions'), dict):
            continue
        dims = {key: _integer(item['dimensions'].get(key), dimensions[key]) for key in ('width', 'depth', 'height')}
        if any(value <= 0 for value in dims.values()):
            continue
        offset = item.get('offset') if isinstance(item.get('offset'), dict) else {}
        local = {axis: origin[axis] + _integer(offset.get(axis)) for axis in ('x', 'y', 'z')}
        parts.append({'part_id': f'{identity}#mass_{index+1}', 'local_origin': local,
                      'dimensions': dims, 'source_params': item})
    return parts


def _outline(rectangles: list[tuple]) -> list[dict]:
    """Outer union edges via interval subtraction; neither bounding-box fill nor voxel allocation."""
    edges = set()
    for x0, z0, x1, z1 in rectangles:
        for direction, plane, lo, hi in (('NORTH',z0,x0,x1),('SOUTH',z1,x0,x1),
                                          ('WEST',x0,z0,z1),('EAST',x1,z0,z1)):
            spans = [(lo,hi)]
            for a,b,c,d in rectangles:
                covers = {'NORTH': b < plane <= d, 'SOUTH': b <= plane < d,
                          'WEST': a < plane <= c, 'EAST': a <= plane < c}[direction]
                if not covers:
                    continue
                cut0, cut1 = (a,c) if direction in ('NORTH','SOUTH') else (b,d)
                remaining = []
                for start,end in spans:
                    if cut1 <= start or cut0 >= end:
                        remaining.append((start,end))
                    else:
                        if start < cut0: remaining.append((start,cut0))
                        if cut1 < end: remaining.append((cut1,end))
                spans = remaining
            edges.update((direction,plane,start,end) for start,end in spans)
    merged = []
    for direction,plane,start,end in sorted(edges):
        if merged and merged[-1][:2] == (direction,plane) and start <= merged[-1][3]:
            merged[-1] = (direction,plane,merged[-1][2],max(end,merged[-1][3]))
        else:
            merged.append((direction,plane,start,end))
    return [{'direction':direction, 'plane':plane, 'start':start, 'end':end} for direction,plane,start,end in merged]


def multi_mass_geometry(component: dict) -> dict:
    parts = resolve_mass_parts(component)
    params = component.get('params') or {}
    rectangles = []
    exact = True
    for part in parts:
        local, dims = part['local_origin'], part['dimensions']
        part_params = {**params, **part['source_params']}
        shape = str(part_params.get('shape') or part_params.get('footprint_shape') or part_params.get('footprintShape') or 'rectangle').lower()
        pattern = str(part_params.get('plan_type') or part_params.get('planType') or 'none').lower()
        exact = exact and shape in ('rectangle','rect','box') and pattern in ('rectangle','rect','box','none')
        rectangles.append((local['x'],local['z'],local['x']+dims['width'],local['z']+dims['depth']))
    envelope = {'min_x':min(r[0] for r in rectangles), 'min_z':min(r[1] for r in rectangles),
                'max_x':max(r[2] for r in rectangles), 'max_z':max(r[3] for r in rectangles),
                'min_y':min(p['local_origin']['y'] for p in parts),
                'max_y':max(p['local_origin']['y']+p['dimensions']['height'] for p in parts)}
    report_parts = [{key:value for key,value in part.items() if key != 'source_params'} for part in parts]
    for part in report_parts:
        part['roof_y_local'] = part['local_origin']['y']+part['dimensions']['height']-1
    return {'parts':report_parts, 'envelope_local':envelope,
            'footprint_status':'rectangular_union' if exact and len(parts) <= 256 else 'bounds_only',
            'outline_local':_outline(rectangles) if exact and len(parts) <= 256 else [],
            'direction_convention':'minecraft_world', 'coverage_status':'parameters_only'}
