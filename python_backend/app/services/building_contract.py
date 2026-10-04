"""Versioned, user-owned requirements and component identity for the existing LlmPlan route."""
from __future__ import annotations

from copy import deepcopy
import re
from typing import Any

SCHEMA = 'formacraft.building_contract.v1'
LEGACY_ENTRANCE_FACING = {'NORTH': 'SOUTH', 'SOUTH': 'NORTH', 'EAST': 'WEST', 'WEST': 'EAST'}
NUMBERS = {'一': 1, '二': 2, '两': 2, '三': 3, '四': 4, '五': 5, '六': 6, '七': 7, '八': 8, '九': 9, '十': 10}
HOSTED = {'ROOF', 'ROOF_STRUCTURE', 'FOUNDATION', 'FACADE_WINDOWS', 'FACADE', 'ENTRANCE',
          'CROWN', 'CUPOLA', 'DOME', 'MASS_SECONDARY', 'DECOR_DETAIL', 'BALCONY', 'STRUCTURE', 'ASSEMBLY'}



def _positive_match(text: str, match: re.Match) -> bool:
    prefix = text[max(0, match.start()-12):match.start()]
    return not re.search(r'(?:不要|不用|不使用|不采用|避免)(?:用|采用|使用)?\s*$', prefix)


def _extract_global_requirements(text: str) -> list[dict]:
    """Only unambiguous supported expressions; conflicting values remain unscoped, never guessed."""
    result = []
    patterns = {
        'width': r'(?:宽度|宽)\s*(\d+)\s*格',
        'depth': r'(?:深度|深)\s*(\d+)\s*格',
        'floor_height': r'每层(?:的)?(?:高度|高)\s*(\d+)\s*格',
        'floor_count': r'(?<![\d每])([一二两三四五六七八九十]|\d+)\s*层(?:的)?(?:住宅|建筑|大厅|石砖|塔楼|楼|民居|房屋|别墅|高|，|、|。)',
    }
    for key, pattern in patterns.items():
        matches = [m for m in re.finditer(pattern, text) if _positive_match(text, m)]
        if not matches: continue
        values = {NUMBERS[m[1]] if m[1] in NUMBERS else int(m[1]) for m in matches}
        result.append({'id': f'req_{key}', 'property': key, 'value': next(iter(values)) if len(values) == 1 else None,
                       'unit': 'floors' if key == 'floor_count' else 'blocks', 'source': 'user_explicit',
                       'source_text': [m[0] for m in matches], 'scope': 'all_main_masses' if len(values) == 1 else 'unresolved',
                       'priority': 'hard', 'status': 'pending' if len(values) == 1 else 'unsupported_scope'})
    if re.search(r'平屋顶|平顶', text) and not re.search(r'不要.{0,3}(?:平屋顶|平顶)', text):
        result.append({'id': 'req_roof_type', 'property': 'roof_type', 'value': 'flat', 'source': 'user_explicit',
                       'source_text': ['平屋顶' if '平屋顶' in text else '平顶'], 'scope': 'all_main_masses', 'priority': 'hard', 'status': 'pending'})
    if re.search(r'(?:不(?:要|需)|暂不).{0,18}复杂装饰', text):
        result.append({'id': 'req_no_complex_decor', 'property': 'no_complex_decor', 'value': True,
                       'source': 'user_explicit', 'source_text': [re.search(r'(?:不(?:要|需)|暂不).{0,18}复杂装饰', text)[0]],
                       'scope': 'plan', 'priority': 'hard', 'status': 'pending'})
    materials = {'石砖': 'minecraft:stone_bricks', '橡木木板': 'minecraft:oak_planks',
                 '橡木板': 'minecraft:oak_planks', '橡木': 'minecraft:oak_planks', '云杉木板': 'minecraft:spruce_planks',
                 '砖块': 'minecraft:bricks', '圆石': 'minecraft:cobblestone', '石英块': 'minecraft:quartz_block'}
    names = '|'.join(sorted(materials, key=len, reverse=True))
    for prop, role in (('wall_block', '外墙|墙体'), ('floor_block', '楼板|地板')):
        pattern = rf'(?:(?P<before>{names})(?:制)?(?:{role})|(?:{role})(?:使用|采用|用)(?P<after>{names}))'
        hits = [m for m in re.finditer(pattern, text) if _positive_match(text, m)]
        if not hits:
            continue
        values = {materials[m.group('before') or m.group('after')] for m in hits}
        result.append({'id': 'req_' + prop, 'property': prop,
                       'value': next(iter(values)) if len(values) == 1 else None,
                       'source': 'user_explicit', 'source_text': [m[0] for m in hits],
                       'scope': 'all_main_masses' if len(values) == 1 else 'unresolved',
                       'priority': 'hard', 'status': 'pending' if len(values) == 1 else 'unsupported_scope'})
    hits = [m for m in re.finditer(r'(?:正门|入口)(?:位于|朝向|朝|向)(?:建筑)?([东南西北])(?:侧|面|方)?', text) if _positive_match(text, m)]
    if hits:
        values = {{'东': 'EAST', '南': 'SOUTH', '西': 'WEST', '北': 'NORTH'}[m[1]] for m in hits}
        result.append({'id': 'req_entrance_facing', 'property': 'entrance_facing',
                       'direction_convention': 'minecraft_world',
                       'runtime_field_value': LEGACY_ENTRANCE_FACING[next(iter(values))] if len(values) == 1 else None,
                       'value': next(iter(values)) if len(values) == 1 else None,
                       'source': 'user_explicit', 'source_text': [m[0] for m in hits],
                       'scope': 'all_main_masses' if len(values) == 1 else 'unresolved',
                       'priority': 'hard', 'status': 'pending' if len(values) == 1 else 'unsupported_scope'})
    return result



def extract_requirements(text: str) -> list[dict]:
    """Recognize explicit ordinal declarations; never infer ownership from component order."""
    declarations = list(re.finditer(
        r'第([一二两三四五六七八九十]|\d+)栋(?:建筑|住宅|房屋)?[：:，,\s]*(?=宽|深|每层|使用|采用|外墙|墙体|楼板|地板|正门|入口|平屋顶|[一二两三四五六七八九十\d]+层)', text))
    if not declarations:
        return _extract_global_requirements(text)
    events = [(m.start(), m.end(), 'building_' + str(NUMBERS[m[1]] if m[1] in NUMBERS else int(m[1])))
              for m in declarations]
    events += [(m.start(), m.end(), 'all_main_masses') for m in re.finditer(r'所有建筑|全部建筑|两栋均|两栋都|每栋均|每栋都', text)]
    events.sort()
    result = _extract_global_requirements(text[:events[0][0]])
    for index, (_, end, scope) in enumerate(events):
        stop = events[index+1][0] if index+1 < len(events) else len(text)
        for requirement in _extract_global_requirements(text[end:stop]):
            if requirement['scope'] != 'unresolved' and requirement['scope'] != 'plan':
                requirement['scope'] = scope
            elif requirement['scope'] == 'plan' and scope != 'all_main_masses':
                requirement['scope'] = scope
            requirement['id'] += '_' + scope
            result.append(requirement)
    return result


def request_text(req: Any) -> str:
    current = (getattr(req, 'userMessage', '') or '').strip()
    if extract_requirements(current) or re.search(r'建造|生成|搭建|build|create', current, re.I): return current
    # A short answer to a style question can inherit the last human building request.
    # Never extract requirements from AI history or unrelated earlier requests.
    history = getattr(req, 'chatHistory', None)
    if not isinstance(history, (list, tuple)): return current
    for line in reversed(history):
        if str(line).lower().startswith('player:'):
            previous = str(line)[7:].strip()
            if extract_requirements(previous): return previous + '\n' + current
            break
    return current


def _center(comp: dict) -> tuple[float, float]:
    from .resolved_geometry import mass_center
    return mass_center(comp)


def apply_building_contract(plan: dict, text: str, *, finalize: bool = False) -> dict:
    if not isinstance(plan, dict) or str(plan.get('mode', 'build')).lower() == 'patch': return plan
    out = deepcopy(plan)
    hints = out.setdefault('proportion_hints', {})
    if not isinstance(hints, dict): hints = {}; out['proportion_hints'] = hints
    requirements = extract_requirements(text)
    contract = {'schema': SCHEMA, 'requirements': requirements, 'diagnostics': [], 'validation_stage': 'plan'}
    hints['building_contract'] = contract
    components = [c for c in (out.get('components') or []) if isinstance(c, dict)]
    for comp in components:
        if not isinstance(comp.get('params'), dict): comp['params'] = {}
    supplied = [str((c.get('params') or {}).get('component_id')) for c in components if (c.get('params') or {}).get('component_id')]
    used = set(supplied)
    if len(supplied) != len(used): contract['diagnostics'].append({'code': 'E_COMPONENT_ID_DUPLICATE'})
    for index, comp in enumerate(components):
        params = comp.setdefault('params', {})
        if not isinstance(params, dict): params = {}; comp['params'] = params
        if not params.get('component_id'):
            stem, counter = f'component_{index+1}', 0
            identity = stem
            while identity in used:
                counter += 1; identity = f'{stem}_{counter}'
            params['component_id'] = identity; used.add(identity)
    masses = [c for c in components if c.get('component_type') == 'MASS_MAIN']
    ids = {c['params']['component_id']: c for c in masses}
    has_slots = bool((out.get('layout') or {}).get('slots'))
    for mass in masses:
        mass['params']['building_id'] = mass['params']['component_id']
    for comp in components:
        params = comp['params']
        if comp in masses:
            params['building_id'] = params['component_id']
            continue
        if comp.get('component_type') not in HOSTED: continue
        if params.get('host_id'):
            if params['host_id'] not in ids:
                contract['diagnostics'].append({'code': 'E_HOST_UNKNOWN', 'component_id': params['component_id'], 'host_id': params['host_id']})
            elif has_slots and ids[params['host_id']].get('slot_id') != comp.get('slot_id'):
                contract['diagnostics'].append({'code': 'E_HOST_COORDINATE_FRAME', 'component_id': params['component_id'], 'host_id': params['host_id']})
            else:
                params['building_id'] = ids[params['host_id']]['params']['building_id']
            continue
        # Connectors have two hosts and cannot be represented by a guessed single owner.
        if any('bridge' in str(f).lower() or '连廊' in str(f) for f in comp.get('features', [])): continue
        candidates = [m for m in masses if m.get('slot_id') == comp.get('slot_id')] if has_slots else masses
        if not candidates: continue
        x, z = _center(comp)
        host = min(candidates, key=lambda m: (_center(m)[0]-x)**2 + (_center(m)[1]-z)**2)
        params['host_id'] = host['params']['component_id']
        params['building_id'] = host['params']['building_id']
        params['host_source'] = 'legacy_geometry_inference'
    bindings = {}
    for mass in masses:
        scope = mass['params'].get('requirement_scope')
        if isinstance(scope, str):
            bindings.setdefault(scope, []).append(mass)
    contract['scope_bindings'] = {scope: [m['params']['component_id'] for m in targets]
                                  for scope, targets in bindings.items()}
    slots = {slot.get('slot_id'): slot for slot in (out.get('layout') or {}).get('slots', []) if isinstance(slot, dict)}
    for requirement in requirements:
        scope = requirement['scope']
        if scope == 'unresolved':
            continue
        targets = masses if scope in ('all_main_masses', 'plan') else bindings.get(scope, [])
        if not targets or (scope.startswith('building_') and len(targets) != 1):
            requirement['status'] = 'unverified' if not masses else 'unresolved_binding'
            if masses:
                contract['diagnostics'].append({'code': 'E_REQUIREMENT_SCOPE', 'scope': scope, 'requirement_id': requirement['id']})
            continue
        target_ids = {m['params']['component_id'] for m in targets}
        owned = components if scope in ('all_main_masses', 'plan') else [
            c for c in components if c['params'].get('host_id') in target_ids or c['params']['component_id'] in target_ids]
        key, expected = requirement['property'], requirement['value']
        if key == 'no_complex_decor':
            invalid = [c['params']['component_id'] for c in owned if c.get('component_type') in ('CROWN', 'CUPOLA', 'DOME')]
        elif key in ('width', 'depth'):
            from .resolved_geometry import body_dimensions
            invalid = [c['params']['component_id'] for c in targets if body_dimensions(c).get(key) != expected]
        elif key in ('floor_count', 'floor_height', 'wall_block', 'floor_block'):
            invalid = [c['params']['component_id'] for c in targets if c['params'].get(key) != expected]
        elif key == 'entrance_facing':
            def facing(c):
                slot = slots.get(c.get('slot_id'))
                if slot is not None:
                    runtime = slot.get('facing') or 'SOUTH'
                else:
                    runtime = (out.get('global_constraints') or {}).get('facing') or 'SOUTH'
                return LEGACY_ENTRANCE_FACING.get(runtime)
            invalid = [c['params']['component_id'] for c in targets if facing(c) != expected]
        elif key == 'roof_type':
            invalid = []
            for mass in targets:
                roofs = [c for c in owned if c.get('component_type') in ('ROOF', 'ROOF_STRUCTURE')
                         and c['params'].get('host_id') == mass['params']['component_id']]
                invalid += [c['params']['component_id'] for c in (roofs or [mass]) if c['params'].get('roof_type') != expected]
        else:
            continue
        requirement['status'] = 'mismatch' if invalid else 'planned'
        requirement['target_components'] = sorted(target_ids)
        requirement['mismatched_components'] = invalid
    from .resolved_geometry import resolve_buildings
    contract['resolved_buildings'] = resolve_buildings(out)
    for building in contract['resolved_buildings']:
        if not building['floor_layout_fits']:
            contract['diagnostics'].append({'code': 'E_FLOOR_ENVELOPE', 'component_id': building['component_id']})
    failures = [r for r in requirements if r['status'] == 'mismatch']
    if finalize and not out.get('capability_gap') and (failures or contract['diagnostics']):
        out['plan_status'] = 'capability_gap'
        out['capability_gap'] = {'code': 'E_BUILDING_CONTRACT', 'message': '建筑方案未满足明确要求或主体引用无效',
                                 'path': 'proportion_hints.building_contract',
                                 'suggestions': [f"{r['id']}: expected {r['value']}, components {r['mismatched_components']}" for r in failures]
                                                + [str(d) for d in contract['diagnostics']]}
    return out
