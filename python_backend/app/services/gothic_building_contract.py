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
    plan.setdefault('proportion_hints', {})['gothic_building_contract'] = {
        'schema': 'formacraft.gothic_building.v1', 'requirements': report,
        'verification_level': 'plan_parameters_only'}
