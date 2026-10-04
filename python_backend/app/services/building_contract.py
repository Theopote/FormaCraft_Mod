"""Versioned, user-owned requirements and component identity for the existing LlmPlan route."""
from __future__ import annotations

from copy import deepcopy
import re
from typing import Any

SCHEMA = 'formacraft.building_contract.v1'
NUMBERS = {'一': 1, '二': 2, '两': 2, '三': 3, '四': 4, '五': 5, '六': 6, '七': 7, '八': 8, '九': 9, '十': 10}
HOSTED = {'ROOF', 'ROOF_STRUCTURE', 'FOUNDATION', 'FACADE_WINDOWS', 'FACADE', 'ENTRANCE',
          'MASS_SECONDARY', 'DECOR_DETAIL', 'BALCONY', 'STRUCTURE', 'ASSEMBLY'}


def extract_requirements(text: str) -> list[dict]:
    """Only unambiguous supported expressions; conflicting values remain unscoped, never guessed."""
    result = []
    patterns = {
        'width': r'(?:宽度|宽)\s*(\d+)\s*格',
        'depth': r'(?:深度|深)\s*(\d+)\s*格',
        'floor_height': r'每层(?:的)?(?:高度|高)\s*(\d+)\s*格',
        'floor_count': r'(?<![\d每])([一二两三四五六七八九十]|\d+)\s*层(?:的)?(?:住宅|建筑|大厅|石砖|塔楼|楼|民居|房屋|别墅|高|，|、|。)',
    }
    for key, pattern in patterns.items():
        matches = list(re.finditer(pattern, text))
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
    p, dims, params = comp.get('relative_position') or {}, comp.get('dimensions') or {}, comp.get('params') or {}
    x, z = float(p.get('x', 0)), float(p.get('z', 0))
    if params.get('anchor_mode') == 'min_corner':
        x += (float(dims.get('width', 1))-1)/2
        z += (float(dims.get('depth', 1))-1)/2
    return x, z


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
    for requirement in requirements:
        if requirement['scope'] == 'unresolved': continue
        key, expected = requirement['property'], requirement['value']
        if key == 'no_complex_decor':
            invalid = [c['params']['component_id'] for c in components if c.get('component_type') in ('CROWN', 'CUPOLA', 'DOME')]
        elif key in ('width', 'depth'):
            invalid = [c['params']['component_id'] for c in masses if (c.get('dimensions') or {}).get(key) != expected]
        elif key in ('floor_count', 'floor_height'):
            invalid = [c['params']['component_id'] for c in masses if c['params'].get(key) != expected]
        elif key == 'roof_type':
            roofs = [c for c in components if c.get('component_type') in ('ROOF', 'ROOF_STRUCTURE')]
            invalid = [c['params']['component_id'] for c in roofs if c['params'].get('roof_type') != expected]
            invalid += [c['params']['component_id'] for c in masses if not roofs and c['params'].get('roof_type') != expected]
        else: continue
        requirement['status'] = 'unverified' if not masses else ('mismatch' if invalid else 'planned')
        requirement['mismatched_components'] = invalid
    failures = [r for r in requirements if r['status'] == 'mismatch']
    if finalize and not out.get('capability_gap') and (failures or contract['diagnostics']):
        out['plan_status'] = 'capability_gap'
        out['capability_gap'] = {'code': 'E_BUILDING_CONTRACT', 'message': '建筑方案未满足明确要求或主体引用无效',
                                 'path': 'proportion_hints.building_contract',
                                 'suggestions': [f"{r['id']}: expected {r['value']}, components {r['mismatched_components']}" for r in failures]
                                                + [str(d) for d in contract['diagnostics']]}
    return out
