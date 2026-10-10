"""Exact optional Gothic elements; never infer multi-building scope."""
import re

_PATTERNS = {
    'includeRoseWindow': r'(?:不要|不设|不生成|取消)玫瑰(?:花)?窗|\bno rose window\b',
    'includeButtresses': r'(?:不要|不设|不生成|取消)(?:飞)?扶壁|\bno (?:flying )?buttresses\b',
    'includeTowers': r'(?:不要|不设|不生成|取消)双塔|\bno twin towers\b',
}

def apply_gothic_building_contract(plan: dict, text: str) -> None:
    targets = [c for c in plan.get('components', []) if isinstance(c, dict)
               and c.get('component_type') == 'STRUCTURE'
               and ('typology:gothic_cathedral_hall' in c.get('features', [])
                    or c.get('params', {}).get('typology_id') == 'gothic_cathedral_hall')]
    scoped = bool(re.search(r'左栋|右栋|第.+?栋|东侧建筑|西侧建筑', text))
    report = []
    for key, pattern in _PATTERNS.items():
        match = re.search(pattern, text, re.IGNORECASE)
        if not match:
            continue
        status = 'planned' if len(targets) == 1 and not scoped else 'unresolved_building_scope'
        ids = []
        if status == 'planned':
            params = targets[0].setdefault('params', {})
            params[key] = False
            ids = [params.get('component_id')]
        report.append({'property': key, 'value': False, 'source_text': match[0],
                       'status': status, 'target_components': ids})
    if len(targets) == 1 and not scoped:
        params = targets[0].get('params', {})
        for requirement in plan.get('proportion_hints', {}).get('building_contract', {}).get('requirements', []):
            key = {'width': 'width', 'depth': 'depth', 'entrance_facing': 'facing'}.get(requirement['property'])
            if key and requirement.get('scope') == 'all_main_masses' and params.get(key) == requirement['value']:
                requirement['status'] = 'planned'
                requirement['target_components'] = [params.get('component_id')]
                requirement['verification_level'] = 'native_parameters_only'
    plan.setdefault('proportion_hints', {})['gothic_building_contract'] = {
        'schema': 'formacraft.gothic_building.v1', 'requirements': report,
        'verification_level': 'plan_parameters_only'}

def repair_gothic_route(plan: dict, text: str) -> None:
    """Repair only the generic single-church native route, not named landmarks/compounds."""
    hints = plan.setdefault('proportion_hints', {})
    if hints.get('typology') != 'gothic_cathedral_hall':
        return
    if not re.search(r'哥特(?:式)?教堂|gothic (?:church|cathedral)', text, re.I):
        return
    if re.search(r'左栋|右栋|第.+?栋|建筑群|两座|两栋|巴黎圣母院|科隆|notre dame|cologne|chartres|sagrada|横厅|后殿|平屋顶|白色|木质|材料|墙厚|楼板厚', text, re.I):
        return
    for pattern, low, high, odd in [(r'宽(\d+)格', 17, 101, True), (r'深(\d+)格', 25, 151, True), (r'中殿墙高(\d+)格', 12, 40, False)]:
        values = {int(m[1]) for m in re.finditer(pattern, text)}
        if len(values) > 1 or any(v < low or v > high or odd and v % 2 == 0 for v in values):
            hints['gothic_route_repair'] = {'route': 'not_repaired', 'reason': 'unsupported_or_conflicting_dimensions'}
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
    params.setdefault('component_id', 'gothic_church')
    params['typology_id'] = 'gothic_cathedral_hall'
    for key, pattern in [('width', r'宽(\d+)格'), ('depth', r'深(\d+)格'), ('wallHeight', r'中殿墙高(\d+)格')]:
        values = {int(m[1]) for m in re.finditer(pattern, text)}
        if len(values) == 1: params[key] = values.pop()
    directions = set(re.findall(r'入口朝([东南西北])', text))
    if len(directions) == 1:
        params['facing'] = {'东': 'EAST', '南': 'SOUTH', '西': 'WEST', '北': 'NORTH'}[directions.pop()]
        params.pop('layout', None)  # User facing wins over generated nested facing.
    component['params'] = params
    component['dimensions'] = {'width': params['width'], 'depth': params['depth'],
                               'height': params['wallHeight'] + 12}
    layout = plan.setdefault('layout', {})
    old_slots = layout.get('slots', [])
    chosen = next((s for s in old_slots if s.get('slot_id') == component.get('slot_id')), None)
    layout['slots'] = [chosen or {'slot_id': component.get('slot_id'), 'anchor': {'x': 0, 'y': 0, 'z': 0}, 'facing': params['facing']}]
    layout['skeleton_type'] = 'GRID_BAY'
    hints['gothic_route_repair'] = {'route': 'native_typology', 'verification_level': 'plan_parameters_only'}
    if (plan.get('capability_gap') or {}).get('code') == 'E_BUILDING_CONTRACT':
        plan.pop('capability_gap', None)
        plan.pop('plan_status', None)
