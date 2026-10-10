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


def _normalize_request_text(text: str) -> str:
    return re.sub(r'(?<=[\u4e00-\u9fff])\s+(?=[\u4e00-\u9fff])', '', text)


def _extract_global_requirements(text: str) -> list[dict]:
    """Only unambiguous supported expressions; conflicting values remain unscoped, never guessed."""
    result = []
    patterns = {
        'width': r'(?:宽度|宽)\s*(\d+)\s*格',
        'depth': r'(?:深度|深)\s*(\d+)\s*格',
        'floor_height': r'每层(?:的)?(?:高度|高)\s*(\d+)\s*格',
        'net_height': r'室内净高\s*(\d+)\s*格',
        'floor_count': r'(?<![\d每])([一二两三四五六七八九十]|\d+)\s*层(?:的)?(?:[^，。；\n]{0,10})?(?:住宅|建筑|大厅|塔楼|民居|房屋|别墅|高|，|、|。)',
    }
    for key, pattern in patterns.items():
        matches = [m for m in re.finditer(pattern, text) if _positive_match(text, m)]
        if not matches: continue
        values = {NUMBERS[m[1]] if m[1] in NUMBERS else int(m[1]) for m in matches}
        result.append({'id': f'req_{key}', 'property': key, 'value': next(iter(values)) if len(values) == 1 else None,
                       'unit': 'floors' if key == 'floor_count' else 'blocks', 'source': 'user_explicit',
                       'source_text': [m[0] for m in matches], 'scope': 'all_main_masses' if len(values) == 1 else 'unresolved',
                       'priority': 'hard', 'status': 'pending' if len(values) == 1 else 'unsupported_scope'})
    if any(_positive_match(text, m) for m in re.finditer(r'双坡屋\s*顶|山墙屋\s*顶', text)):
        result.append({'id': 'req_roof_gable', 'property': 'roof_type', 'value': 'gable', 'source': 'user_explicit',
                       'source_text': ['双坡屋顶'], 'scope': 'all_main_masses', 'priority': 'hard', 'status': 'pending'})
    if any(_positive_match(text, m) for m in re.finditer(r'平屋顶|平顶', text)):
        result.append({'id': 'req_roof_type', 'property': 'roof_type', 'value': 'flat', 'source': 'user_explicit',
                       'source_text': ['平屋顶' if '平屋顶' in text else '平顶'], 'scope': 'all_main_masses', 'priority': 'hard', 'status': 'pending'})
    if re.search(r'山墙[^。；\n]{0,15}(?:不要|不需要|不设|不开|无)[^。；\n]{0,6}(?:窗|开口)|(?:不要|不需要|不设|不开)[^。；\n]{0,6}山墙[^。；\n]{0,6}窗|(?:普通)?封闭山墙', text):
        result.append({'id': 'req_gable_windows', 'property': 'gable_windows', 'value': False,
                       'source': 'user_explicit', 'source_text': ['山墙禁窗或封闭山墙'],
                       'scope': 'all_main_masses', 'priority': 'hard', 'status': 'pending'})
    if any(_positive_match(text, m) for m in re.finditer(r'玻璃窗', text)):
        result.append({'id': 'req_glass_windows', 'property': 'glass_block', 'value': 'minecraft:glass',
                       'source': 'user_explicit', 'scope': 'all_main_masses', 'priority': 'hard', 'status': 'pending'})
    for prop, pattern in (
        ('roof_type', r'(?:不要|不需要|不需)(?:生成|添加|建造)?屋顶|无屋顶'),
        ('window_style', r'(?:不要|不需要|不需)(?:生成|添加)?(?:窗户|窗洞)|不开窗|无窗(?:户)?'),
        ('entrance_type', r'(?:不要|不需要|不需)(?:生成|添加)?(?:入口|门洞)|无入口'),
    ):
        hits = list(re.finditer(pattern, text))
        if prop == 'window_style':
            hits = [m for m in hits if not re.search(r'山墙[^。；\n]*$', text[max(0, m.start()-30):m.start()])]
        if hits:
            result.append({'id': 'req_disable_' + prop, 'property': prop, 'value': 'none',
                           'source': 'user_explicit', 'source_text': [m[0] for m in hits],
                           'scope': 'all_main_masses', 'priority': 'hard', 'status': 'pending'})
    if re.search(r'(?:不(?:要|需)|暂不).{0,18}复杂装饰', text):
        result.append({'id': 'req_no_complex_decor', 'property': 'no_complex_decor', 'value': True,
                       'source': 'user_explicit', 'source_text': [re.search(r'(?:不(?:要|需)|暂不).{0,18}复杂装饰', text)[0]],
                       'scope': 'plan', 'priority': 'hard', 'status': 'pending'})
    materials = {'石砖': 'minecraft:stone_bricks', '橡木木板': 'minecraft:oak_planks',
                 '橡木板': 'minecraft:oak_planks', '橡木': 'minecraft:oak_planks', '云杉木板': 'minecraft:spruce_planks',
                 '砖块': 'minecraft:bricks', '圆石': 'minecraft:cobblestone', '石英块': 'minecraft:quartz_block',
                 '深板岩瓦': 'minecraft:deepslate_tiles', '深板岩砖': 'minecraft:deepslate_bricks',
                 '深色橡木板': 'minecraft:dark_oak_planks'}
    names = '|'.join(sorted(materials, key=len, reverse=True))
    for prop, role in (('wall_block', '外墙|墙体'), ('floor_block', '楼板|地板'), ('roof_block', '屋顶|屋面')):
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
    text = _normalize_request_text(text)
    declarations = list(re.finditer(
        r'第([一二两三四五六七八九十]|\d+)栋(?:建筑|住宅|房屋)?[：:，,\s]*(?:是)?', text))
    directional = list(re.finditer(r'(左|右)栋(?:建筑|住宅|房屋)?[：:，,\s]*(?:使用|采用|是)?', text))
    cardinal = list(re.finditer(r'(东|西|南|北)侧(?:的)?(?:住宅|建筑|房屋|民居|别墅)', text))
    if not declarations and not directional and not cardinal:
        return _extract_global_requirements(text)
    events = [(m.start(), m.end(), 'building_' + str(NUMBERS[m[1]] if m[1] in NUMBERS else int(m[1])))
              for m in declarations]
    events += [(m.start(), m.end(), 'building_1' if m[1] == '左' else 'building_2') for m in directional]
    events += [(m.start(), m.end(), 'all_main_masses') for m in re.finditer(r'所有建筑|全部建筑|两栋均|两栋都|每栋均|每栋都', text)]
    events += [(m.start(), m.end(), 'building_' + {'东': 'east', '西': 'west', '南': 'south', '北': 'north'}[m[1]])
               for m in cardinal]
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
    text = _normalize_request_text(text)
    out = deepcopy(plan)
    from .gothic_building_contract import repair_gothic_route
    repair_gothic_route(out, text)
    hints = out.setdefault('proportion_hints', {})
    if not isinstance(hints, dict): hints = {}; out['proportion_hints'] = hints
    requirements = extract_requirements(text)
    contract = {'schema': SCHEMA, 'requirements': requirements, 'diagnostics': [], 'validation_stage': 'plan'}
    hints['building_contract'] = contract
    from .architecture_intent import describe_architecture
    hints['architecture_intent'] = describe_architecture(text)
    hints['architecture_intent']['materials'] = [deepcopy(r) for r in requirements
                                               if r['property'] in ('wall_block', 'floor_block', 'roof_block')]
    components = [c for c in (out.get('components') or []) if isinstance(c, dict)]
    slots = (out.get('layout') or {}).get('slots') or []
    aliases = {s['id']: s['slot_id'] for s in slots if isinstance(s, dict) and s.get('id') and s.get('slot_id')}
    for comp in components:
        if not isinstance(comp.get('params'), dict): comp['params'] = {}
        # Older model outputs nested the coordinate frame in params.
        if not comp.get('slot_id') and comp['params'].get('slot_id'):
            comp['slot_id'] = comp['params']['slot_id']
        if comp.get('slot_id') in aliases:
            comp['slot_id'] = aliases[comp['slot_id']]
    # Repair legacy plan-space coordinates duplicated in a building slot anchor.
    # Only exact center repetition is evidence; min_corner offsets remain local.
    for slot in slots:
        if not isinstance(slot, dict): continue
        key = slot.get('slot_id', slot.get('id'))
        anchor = slot.get('anchor')
        if not isinstance(anchor, dict) or not any(anchor.get(a, 0) for a in ('x', 'z')): continue
        bodies = [c for c in components if c.get('component_type') == 'MASS_MAIN' and c.get('slot_id') == key]
        if len(bodies) != 1: continue
        body = bodies[0]
        pos = body.get('relative_position') or {}
        if body['params'].get('anchor_mode', 'center') != 'center' or not all(
                isinstance(anchor.get(a), (int, float)) and pos.get(a) == anchor[a] for a in ('x', 'z')): continue
        for c in components:
            if c.get('slot_id') != key or not isinstance(c.get('relative_position'), dict): continue
            for axis in ('x', 'z'):
                c['relative_position'][axis] -= anchor[axis]
            c['params']['coordinate_frame_source'] = 'legacy_plan_space'
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
    # Global attributes cannot encode different materials for separate buildings.
    attrs = out.get('style_attributes') or {}
    for role in ('wall', 'floor', 'roof'):
        value = attrs.get(role + '_material')
        if isinstance(value, str) and '/' in value and 'building_' in value and masses and all(
                isinstance(m['params'].get(role + '_block'), str) and m['params'][role + '_block'] for m in masses):
            attrs.pop(role + '_material', None)
    for mass in masses:
        params = mass['params']
        own = [r for r in requirements if r['scope'] in ('all_main_masses', 'plan', params.get('requirement_scope'))]
        for prop, kind, positive in (('window_style', 'FACADE_WINDOWS', r'窗户|玻璃窗'),
                                     ('entrance_type', 'ENTRANCE', r'入口|正门')):
            if params.get(prop) == 'none' and re.search(positive, text) and not any(
                    r['property'] == prop and r['value'] == 'none' for r in own) and any(
                    c.get('component_type') == kind and c['params'].get('host_id') == params['component_id'] for c in components):
                params.pop(prop)
    # Legacy "none" on the body meant delegate roofing to a hosted component.
    # Only reconcile when user requirements explicitly request roof material/type;
    # a real user opt-out must still fail rather than being silently overwritten.
    for mass in masses:
        params = mass['params']
        scope = params.get('requirement_scope')
        own = [r for r in requirements if r['scope'] in ('all_main_masses', 'plan', scope)]
        prohibited = any(r['property'] == 'roof_type' and r['value'] == 'none' for r in own)
        requested = any(r['property'] in ('roof_block', 'roof_type') and r['value'] not in (None, 'none') for r in own)
        roofs = [c for c in components if c.get('component_type') in ('ROOF', 'ROOF_STRUCTURE')
                 and c['params'].get('host_id') == params['component_id']]
        if params.get('roof_type') == 'none' and requested and not prohibited and roofs:
            params['roof_type'] = roofs[0]['params'].get('roof_type', 'gable')
    has_slots = bool((out.get('layout') or {}).get('slots'))
    for mass in masses:
        mass['params']['building_id'] = mass['params']['component_id']
    by_id = {c['params']['component_id']: c for c in components}
    for comp in components:
        params = comp['params']
        if comp in masses:
            params['building_id'] = params['component_id']
            continue
        if comp.get('component_type') not in HOSTED: continue
        if params.get('host_source') == 'legacy_geometry_inference':
            params.pop('host_id', None)
        if params.get('host_id'):
            # Roof ornaments may name the roof as their host. Resolve its ownership
            # chain to the building before enforcing the building coordinate frame.
            original_host = params['host_id']
            host = original_host
            visited = {params['component_id']}
            while host not in ids and host in by_id and host not in visited:
                visited.add(host)
                parent = by_id[host]['params'].get('host_id')
                if not parent: break
                host = parent
            if host in ids and host != original_host:
                params['attachment_host_id'] = original_host
                params['host_id'] = host
            if params['host_id'] not in ids:
                contract['diagnostics'].append({'code': 'E_HOST_UNKNOWN', 'component_id': params['component_id'], 'host_id': params['host_id']})
            elif has_slots and ids[params['host_id']].get('slot_id') != comp.get('slot_id'):
                frames = {s.get('slot_id', s.get('id')): s for s in slots if isinstance(s, dict)}
                a = frames.get(comp.get('slot_id'), {}).get('anchor')
                b = frames.get(ids[params['host_id']].get('slot_id'), {}).get('anchor')
                pos = comp.get('relative_position')
                if all(isinstance(v, dict) for v in (a, b, pos)) and all(
                        isinstance(v.get(axis), (int, float)) for v in (a, b, pos) for axis in ('x', 'y', 'z')):
                    comp['relative_position'] = {axis: pos[axis] + a[axis] - b[axis] for axis in ('x', 'y', 'z')}
                    comp['slot_id'] = ids[params['host_id']]['slot_id']
                    params['slot_id'] = comp['slot_id']
                    params['building_id'] = ids[params['host_id']]['params']['building_id']
                    params['coordinate_frame_rebased'] = True
                else:
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
    # Left/right are geometric scopes, never the model's component array order.
    if re.search(r'左栋', text) and re.search(r'右栋', text) and len(masses) == 2:
        frames = {s.get('slot_id'): s for s in slots if isinstance(s, dict)}
        ordered = sorted(masses, key=lambda m: _center(m)[0] + frames.get(m.get('slot_id'), {}).get('anchor', {}).get('x', 0))
        coordinates = [_center(m)[0] + frames.get(m.get('slot_id'), {}).get('anchor', {}).get('x', 0) for m in ordered]
        if coordinates[0] != coordinates[1]:
            for index, mass in enumerate(ordered, 1):
                mass['params']['requirement_scope'] = 'building_' + str(index)
    if len(masses) == 2:
        frames = {s.get('slot_id'): s for s in slots if isinstance(s, dict)}
        for axis, low, high in (('x', 'west', 'east'), ('z', 'north', 'south')):
            requested = {r['scope'] for r in requirements}
            if not requested.intersection({'building_' + low, 'building_' + high}):
                continue
            def world_coordinate(mass):
                center = _center(mass)[0 if axis == 'x' else 1]
                return center + frames.get(mass.get('slot_id'), {}).get('anchor', {}).get(axis, 0)
            ordered = sorted(masses, key=world_coordinate)
            if world_coordinate(ordered[0]) == world_coordinate(ordered[1]):
                contract['diagnostics'].append({'code': 'E_REQUIREMENT_DIRECTION_AMBIGUOUS', 'axis': axis})
                continue
            for mass, direction in zip(ordered, (low, high)):
                mass['params']['requirement_scope'] = 'building_' + direction
    for mass in masses:
        own = [r for r in requirements if r['scope'] in ('all_main_masses', mass['params'].get('requirement_scope'))]
        if any(r['property'] == 'glass_block' for r in own):
            mass['params']['glass_block'] = 'minecraft:glass'
            for child in components:
                if child.get('component_type') == 'FACADE_WINDOWS' and child['params'].get('host_id') == mass['params']['component_id']:
                    child['params']['glass_block'] = 'minecraft:glass'
        if any(r['property'] == 'gable_windows' and r['value'] is False for r in own):
            mass['params']['gable_windows'] = False
        clear = next((r['value'] for r in own if r['property'] == 'net_height' and isinstance(r['value'], int)), None)
        if clear is not None:
            floors = max(1, int(mass['params'].get('floor_count', 1)))
            mass['params']['floor_height'] = clear + 1
            mass['dimensions']['height'] = max(mass['dimensions'].get('height', 0), floors * (clear + 1) + 1)
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
            from .resolved_geometry import multi_mass_geometry
            axis = 'x' if key == 'width' else 'z'
            def envelope_size(component):
                envelope = multi_mass_geometry(component)['envelope_local']
                return envelope['max_' + axis] - envelope['min_' + axis]
            requirement['dimension_subject'] = 'building_envelope'
            invalid = [c['params']['component_id'] for c in targets if envelope_size(c) != expected]
        elif key in ('floor_count', 'floor_height', 'wall_block', 'floor_block', 'window_style', 'entrance_type', 'gable_windows', 'glass_block'):
            invalid = [c['params']['component_id'] for c in targets if c['params'].get(key) != expected]
        elif key == 'net_height':
            invalid = [c['params']['component_id'] for c in targets
                       if c['dimensions']['height'] < int(c['params'].get('floor_count', 1)) * (expected + 1) + 1]
        elif key == 'entrance_facing':
            def facing(c):
                slot = slots.get(c.get('slot_id'))
                if slot is not None:
                    runtime = slot.get('facing') or 'SOUTH'
                else:
                    runtime = (out.get('global_constraints') or {}).get('facing') or 'SOUTH'
                return LEGACY_ENTRANCE_FACING.get(runtime)
            invalid = [c['params']['component_id'] for c in targets if facing(c) != expected]
        elif key in ('roof_type', 'roof_block'):
            invalid = []
            for mass in targets:
                roofs = [c for c in owned if c.get('component_type') in ('ROOF', 'ROOF_STRUCTURE')
                         and c['params'].get('host_id') == mass['params']['component_id']]
                invalid += [c['params']['component_id'] for c in (roofs or [mass])
                            if c['params'].get(key, mass['params'].get(key) if key == 'roof_block' else None) != expected]
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
    previous_gap = out.get('capability_gap') or {}
    if not failures and not contract['diagnostics'] and previous_gap.get('code') == 'E_BUILDING_CONTRACT' \
            and previous_gap.get('path') == 'proportion_hints.building_contract':
        out.pop('capability_gap', None)
        out.pop('plan_status', None)
    if finalize and not out.get('capability_gap') and (failures or contract['diagnostics']):
        out['plan_status'] = 'capability_gap'
        out['capability_gap'] = {'code': 'E_BUILDING_CONTRACT', 'message': '建筑方案未满足明确要求或主体引用无效',
                                 'path': 'proportion_hints.building_contract',
                                 'suggestions': [f"{r['id']}: expected {r['value']}, components {r['mismatched_components']}" for r in failures]
                                                + [str(d) for d in contract['diagnostics']]}
    from .window_language_contract import apply_window_language_contract
    apply_window_language_contract(out, text)
    from .building_use_intent import record_building_use_intent
    record_building_use_intent(out, text)
    from .building_use_audit import audit_building_use
    audit_building_use(out)
    from .gothic_building_contract import apply_gothic_building_contract
    apply_gothic_building_contract(out, text)
    return out
