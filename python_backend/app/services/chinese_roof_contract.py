"""Explicit roof controls for one existing native Chinese hall; no style/route guessing."""
import re

_FORMS = {'硬山': 'ying_shan', '悬山': 'xuan_shan', '歇山': 'xie_shan', '庑殿': 'wu_dian'}
_NUMERIC = {
    'roofRise': (r'屋顶升高\s*(\d+)\s*格', 3, 18),
    'roofOverhang': (r'出檐\s*(\d+)\s*格', 0, 3),
    'roofCurve': (r'(?:屋顶|坡面)?曲率\s*(\d+)', 0, 100),
    'roofCornerLift': (r'翘角(?:高度|升高)?\s*(\d+)\s*格', 0, 3),
}


def apply_chinese_roof_contract(plan: dict, text: str) -> None:
    targets = [c for c in plan.get('components', []) if isinstance(c, dict)
               and c.get('component_type') == 'STRUCTURE'
               and (c.get('params', {}).get('typology_id') == 'tailiang_timber_hall'
                    or 'typology:tailiang_timber_hall' in c.get('features', []))]
    if not targets:
        return
    scoped = bool(re.search(r'左栋|右栋|第.+?栋|东侧建筑|西侧建筑|两座|两栋|建筑群', text))
    values = {}
    for match in re.finditer(r'硬山|悬山|歇山|庑殿', text):
        if not re.search(r'(?:不要|不用|不采用|不使用|避免)\s*$', text[max(0, match.start()-8):match.start()]):
            values.setdefault('roofType', []).append((_FORMS[match[0]], match[0]))
    for key, (pattern, low, high) in _NUMERIC.items():
        for match in re.finditer(pattern, text):
            if re.search(r'(?:不要|不用|不采用|不使用|避免)\s*$', text[max(0, match.start()-8):match.start()]):
                continue
            values.setdefault(key, []).append((int(match[1]), match[0]))
    for key, pattern in [('roofCurve', r'不要曲面|不使用曲面|采用直坡'),
                         ('roofCornerLift', r'不要翘角|不设翘角|取消翘角'),
                         ('roofOverhang', r'不要出檐|不设出檐|取消出檐')]:
        for match in re.finditer(pattern, text):
            values.setdefault(key, []).append((0, match[0]))
    report = []
    updates = {}
    for key, hits in values.items():
        unique = {v for v, _ in hits}
        status = 'planned'
        value = next(iter(unique)) if len(unique) == 1 else None
        if len(unique) != 1:
            status = 'conflicting_values'
        elif key in _NUMERIC and not _NUMERIC[key][1] <= value <= _NUMERIC[key][2]:
            status = 'unsupported_value'
        elif len(targets) != 1 or scoped:
            status = 'unresolved_building_scope'
        if status == 'planned':
            updates[key] = value
        report.append({'property': key, 'value': value, 'source_text': [s for _, s in hits],
                       'status': status, 'verification_level': 'plan_parameters_only'})
    if len(targets) == 1:
        params = targets[0].setdefault('params', {})
        effective = dict(params, **updates)
        form = effective.get('roofType', 'wudian')
        lift = effective.get('roofCornerLift', 0)
        if updates and lift and (form not in ('wu_dian', 'wudian', '庑殿', 'xie_shan', 'xieshan', '歇山')
                                 or effective.get('roofOverhang', 1) == 0):
            report.append({'property': 'roofCornerLift', 'value': lift, 'status': 'unsupported_combination',
                           'verification_level': 'plan_parameters_only'})
    failures = [r for r in report if r['status'] != 'planned']
    if not failures and len(targets) == 1:
        targets[0]['params'].update(updates)
        if report and (plan.get('capability_gap') or {}).get('code') == 'E_CHINESE_ROOF_CONTRACT':
            plan.pop('capability_gap', None)
            plan.pop('plan_status', None)
        for item in report:
            item['target_components'] = [targets[0]['params'].get('component_id')]
    elif failures:
        for item in report:
            if item['status'] == 'planned': item['status'] = 'not_applied_due_to_conflict'
        if not plan.get('capability_gap'):
            plan['plan_status'] = 'capability_gap'
            plan['capability_gap'] = {'code': 'E_CHINESE_ROOF_CONTRACT',
                'message': '中式屋顶要求存在冲突、超出范围或建筑归属不明确',
                'path': 'proportion_hints.chinese_roof_contract',
                'suggestions': [f"{r['property']}: {r['status']}" for r in failures]}
    plan.setdefault('proportion_hints', {})['chinese_roof_contract'] = {
        'schema': 'formacraft.chinese_roof.v1', 'requirements': report,
        'verification_level': 'plan_parameters_only'}
