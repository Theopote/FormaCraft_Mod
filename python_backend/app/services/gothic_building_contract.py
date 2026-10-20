"""Gothic route repair and backwards-compatible ornament contract entry point."""
import re


def apply_gothic_building_contract(plan: dict, text: str) -> None:
    from .ornament_contract import apply_ornament_contract
    apply_ornament_contract(plan, text, 'gothic')


def repair_gothic_route(plan: dict, text: str) -> None:
    """Repair only the generic single-church native route, not named landmarks/compounds."""
    hints = plan.setdefault('proportion_hints', {})
    if hints.get('typology') != 'gothic_cathedral_hall':
        return
    if not re.search(r'哥特(?:式)?教堂|gothic (?:church|cathedral)', text, re.I):
        return
    if re.search(r'左栋|右栋|第.+?栋|建筑群|两座|两栋|巴黎圣母院|科隆|notre dame|cologne|chartres|sagrada|平屋顶|白色|木质|材料|墙厚|楼板厚', text, re.I):
        return
    if re.search(r'后殿|apse', text, re.I) and not re.search(r'折角后殿|faceted apse|不要后殿|不设后殿|取消后殿|no apse', text, re.I):
        return
    for pattern, low, high, odd in [(r'宽(\d+)格', 17, 101, True), (r'深(\d+)格', 25, 151, True), (r'中殿墙高(\d+)格', 12, 40, False), (r'屋顶升高(\d+)格', 3, 80, False), (r'塔身高(\d+)格', 16, 80, False), (r'尖顶高(\d+)格', 6, 26, False)]:
        values = {int(m[1]) for m in re.finditer(pattern, text)}
        if len(values) > 1 or any(v < low or v > high or odd and v % 2 == 0 for v in values):
            hints['gothic_route_repair'] = {'route': 'not_repaired', 'reason': 'unsupported_or_conflicting_dimensions'}
            return
    if re.search(r'十字形|十字型|cruciform|(?:设置|增加|带有)横厅', text, re.I):
        requested = re.search(r'宽(\d+)格', text)
        if requested and int(requested[1]) < 25:
            hints['gothic_route_repair'] = {'route': 'not_repaired', 'reason': 'cruciform_width_below_25'}
            return
    from .typology_registry import get_typology
    definition = get_typology('gothic_cathedral_hall')
    params = dict(definition.default_params if definition else {})
    existing = [c for c in plan.get('components', []) if c.get('component_type') == 'STRUCTURE'
                and ('typology:gothic_cathedral_hall' in c.get('features', [])
                     or c.get('params', {}).get('typology_id') == 'gothic_cathedral_hall')]
    if existing:
        if len(existing) != 1: return
        component = existing[0]
        params.update(component.get('params', {}))
    else:
        slots = (plan.get('layout') or {}).get('slots', [])
        slot_id = slots[0].get('slot_id') if slots else 'church_slot'
        component = {'component_type': 'STRUCTURE', 'slot_id': slot_id,
                     'relative_position': {'x': 0, 'y': 0, 'z': 0},
                     'features': ['typology:gothic_cathedral_hall']}
        # Only this one native building remains; it owns its nave, aisles and openings.
        plan['components'] = [component]
    cruciform = bool(re.search(r'十字形|十字型|cruciform|(?:设置|增加|带有)横厅', text, re.I))
    if cruciform:
        requested = re.search(r'宽(\d+)格', text)
        if requested and int(requested[1]) < 25:
            hints['gothic_route_repair'] = {'route': 'not_repaired', 'reason': 'cruciform_width_below_25'}
            return
        params['planVariant'] = 'cruciform'
    elif re.search(r'不要横厅|不设横厅|no transept', text, re.I):
        params['planVariant'] = 'basilica'
    params.setdefault('component_id', 'gothic_church')
    params['typology_id'] = 'gothic_cathedral_hall'
    params['anchorMode'] = 'center'  # Mouse-selected anchor is the body bottom center.
    for key, pattern in [('width', r'宽(\d+)格'), ('depth', r'深(\d+)格'), ('wallHeight', r'中殿墙高(\d+)格'), ('roofRise', r'屋顶升高(\d+)格'), ('towerHeight', r'塔身高(\d+)格'), ('spireHeight', r'尖顶高(\d+)格')]:
        values = {int(m[1]) for m in re.finditer(pattern, text)}
        if len(values) == 1: params[key] = values.pop()
    directions = set(re.findall(r'入口朝([东南西北])', text))
    if len(directions) == 1:
        params['facing'] = {'东': 'EAST', '南': 'SOUTH', '西': 'WEST', '北': 'NORTH'}[directions.pop()]
        params.pop('layout', None)  # User facing wins over generated nested facing.
    component['params'] = params
    import math
    body_width = params['width'] - (8 if params.get('planVariant') == 'cruciform' else 0)
    half_span = (max(9, body_width - 10) + 1) // 2
    roof_rise = params.get('roofRise', math.ceil(half_span * 1.2))
    component['dimensions'] = {'width': params['width'], 'depth': params['depth'],
                               'height': max(params['wallHeight'] + 5 + roof_rise,
                                             params.get('towerHeight', 20) + params.get('spireHeight', 10) + 5)}
    layout = plan.setdefault('layout', {})
    old_slots = layout.get('slots', [])
    chosen = next((s for s in old_slots if s.get('slot_id') == component.get('slot_id')), None)
    layout['slots'] = [chosen or {'slot_id': component.get('slot_id'), 'anchor': {'x': 0, 'y': 0, 'z': 0}, 'facing': params['facing']}]
    layout['skeleton_type'] = 'GRID_BAY'
    hints['gothic_route_repair'] = {'route': 'native_typology', 'verification_level': 'plan_parameters_only'}
    if (plan.get('capability_gap') or {}).get('code') == 'E_BUILDING_CONTRACT':
        plan.pop('capability_gap', None)
        plan.pop('plan_status', None)
