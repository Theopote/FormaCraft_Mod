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
                       'coordinate_stage': 'plan', 'geometry_status': 'parameters_only'})
    return result
