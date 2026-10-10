"""Conservative user window requirements bound to explicit hosted facade scopes."""
from __future__ import annotations
import re

_NUMBERS = {'一': 1, '二': 2, '两': 2, '三': 3, '四': 4, '五': 5, '六': 6, '七': 7, '八': 8, '九': 9, '十': 10}
_NUMBER = r'(\d+|[一二两三四五六七八九十])'

def _number(value: str) -> int:
    return _NUMBERS[value] if value in _NUMBERS else int(value)

def apply_window_language_contract(plan: dict, text: str) -> None:
    rules = []
    patterns = [
        ('front', 'count_per_side', rf'入口两侧(?:每层)?各\s*{_NUMBER}\s*扇窗'),
        ('left_right', 'window_per_wall', rf'(?:每面侧墙|左右侧墙)(?:每层)?(?:各)?\s*{_NUMBER}\s*扇窗'),
    ]
    # Multiple buildings with named scopes need the general building scope binder; do not broaden them.
    scoped_buildings = bool(re.search(r'左栋|右栋|东侧建筑|西侧建筑|第一栋|第二栋', text))
    for wall, key, pattern in patterns:
        for match in re.finditer(pattern, text):
            prefix = text[max(0, match.start()-6):match.start()]
            if re.search(r'不要|不用|避免', prefix):
                continue
            clause = re.split(r'[。；，\n]', text[:match.start()])[-1]
            floor_match = re.search(rf'(?:第)?{_NUMBER}(?:楼|层)(?:的)?\s*$', clause)
            rules.append({'wall': wall, 'property': key, 'value': _number(match[1]), 'source_text': match[0],
                          'floor': _number(floor_match[1]) if floor_match else None, '_position': match.start()})
    # Carry a wall scope only from the immediately preceding count clause.
    for match in re.finditer(rf'(?:第)?{_NUMBER}(?:楼|层)(?:的)?各\s*{_NUMBER}\s*扇窗', text):
        previous = sorted((r for r in rules if r['_position'] < match.start()), key=lambda r: r['_position'])
        antecedent = previous[-1] if previous else None
        between = text[antecedent['_position']:match.start()] if antecedent else ''
        if antecedent and len(re.findall(r'[。；，\n]', between)) == 1 and not re.split(r'[。；，\n]', between)[-1].strip() and not re.search(r'不要|不用|避免', text[max(0, match.start()-6):match.start()]):
            rules.append({'wall': antecedent['wall'], 'property': antecedent['property'],
                          'value': _number(match[2]), 'floor': _number(match[1]),
                          'source_text': match[0], '_position': match.start()})
    for rule in rules:
        rule.pop('_position', None)
    for match in re.finditer(rf'只在(?:第)?{_NUMBER}(?:楼|层)开窗', text):
        if not re.search(r'不要|不用|避免', text[max(0, match.start()-6):match.start()]):
            prefix = re.split(r'[。；，\n]', text[:match.start()])[-1]
            wall = 'left_right' if re.search(r'左右侧墙|每面侧墙', prefix) else 'front' if re.search(r'正面|入口两侧', prefix) else 'unresolved' if re.search(r'墙|侧', prefix) else 'all'
            rules.append({'wall': wall, 'property': 'window_floors', 'value': [_number(match[1])], 'source_text': match[0]})
    report = []
    components = [c for c in plan.get('components', []) if isinstance(c, dict)]
    masses = {c.get('params', {}).get('component_id') for c in components if c.get('component_type') == 'MASS_MAIN'
              and c.get('params', {}).get('component_id')}
    for rule in rules:
        row = dict(rule, source='user_explicit', verification_level='plan_parameters_only')
        competing = [r for r in rules if r['wall'] == rule['wall'] and r['property'] == rule['property'] and r.get('floor') == rule.get('floor')]
        if any(r['value'] != rule['value'] for r in competing):
            row['status'] = 'conflicting_values'
        elif scoped_buildings:
            row['status'] = 'unresolved_building_scope'
        else:
            targets = [c for c in components if c.get('component_type') == 'FACADE_WINDOWS'
                       and c.get('params', {}).get('host_id') in masses
                       and (rule['wall'] == 'all' or c.get('params', {}).get('wall') == rule['wall'])]
            if not targets:
                row['status'] = 'unresolved_facade_scope'
            else:
                for c in targets:
                    if rule.get('floor') is not None:
                        c['params'].setdefault('window_counts_by_floor', {}).setdefault(str(rule['floor']), {})[rule['property']] = rule['value']
                    else:
                        c['params'][rule['property']] = rule['value'][:] if isinstance(rule['value'], list) else rule['value']
                row['status'] = 'planned'
                row['target_components'] = [c['params'].get('component_id') for c in targets]
        report.append(row)
    plan.setdefault('proportion_hints', {})['window_language_report'] = {
        'schema': 'formacraft.window_language.v1', 'requirements': report}
