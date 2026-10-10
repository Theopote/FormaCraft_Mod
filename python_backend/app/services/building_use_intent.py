"""Conservative use detection, independent of architectural style and geometry."""
import re

_PATTERNS = {
    'residential': r'住宅|别墅|\b(?:house|residence|villa)\b',
    'shop': r'商店|店铺|\bshop\b',
    'public_hall': r'公共大厅|公共会堂|\bpublic hall\b',
}
_GUIDANCE = {
    'residential': 'Plan domestic living space and privacy; include circulation between requested floors. Do not add partitions or furniture when the user requests a hollow interior.',
    'shop': 'Plan a public sales area with a visible customer entrance; a counter or storage area is optional and subordinate to user requirements. Do not default to a residential floor plan.',
    'public_hall': 'Plan an open communal gathering space with clear internal circulation. Avoid unnecessary residential partitions; do not invent extra exits or room counts as user requirements.',
}

def identify_building_uses(text: str) -> list[str]:
    return [use for use, pattern in _PATTERNS.items() if re.search(pattern, text or '', re.IGNORECASE)]

def building_use_guidance(text: str) -> str:
    uses = identify_building_uses(text)
    if not uses:
        return ''
    lines = ['Building use is independent of style, roof shape and structural typology. Explicit user requirements and opt-outs win.']
    if len(uses) > 1:
        lines.append('Multiple uses detected: bind each use to the explicitly requested building or part; do not apply every use to every building.')
    lines.extend(f'{use}: {_GUIDANCE[use]}' for use in uses)
    return '\n'.join(lines)

def record_building_use_intent(plan: dict, text: str) -> None:
    uses = identify_building_uses(text)
    masses = [c for c in plan.get('components', []) if c.get('component_type') == 'MASS_MAIN'
              and c.get('params', {}).get('component_id')]
    declarations = list(re.finditer(r'(左栋|右栋|第[一二12]栋)', text))
    bindings = []
    # Reuse exact explicit contract scopes; never use array order or a style name.
    for index, match in enumerate(declarations):
        end = declarations[index+1].start() if index+1 < len(declarations) else len(text)
        segment = text[match.end():end]
        requested = identify_building_uses(segment)
        scope = 'building_1' if match[0] in ('左栋', '第一栋', '第1栋') else 'building_2'
        targets = [m for m in masses if m.get('params', {}).get('requirement_scope') == scope]
        if len(targets) != 1:
            targets = []
        status = 'bound' if len(requested) == 1 and targets else 'ambiguous_use' if len(requested) > 1 else 'unresolved_scope'
        bindings.append({'scope': scope, 'uses': requested, 'status': status,
                         'target_components': [m['params']['component_id'] for m in targets] if status == 'bound' else []})
    if not declarations and len(uses) == 1 and len(masses) == 1:
        bindings.append({'scope': 'single_main_building', 'uses': uses, 'status': 'bound',
                         'target_components': [masses[0]['params']['component_id']]})
    # Repeated contradictory declarations do not silently select the last value.
    for binding in bindings:
        competing = [b for b in bindings if b['scope'] == binding['scope']]
        if any(b['uses'] != binding['uses'] for b in competing):
            binding['status'] = 'conflicting_uses'
            binding['target_components'] = []
    plan.setdefault('proportion_hints', {})['building_use_intent'] = {
        'schema': 'formacraft.building_use.v1', 'detected_uses': uses,
        'scope_status': 'bound' if bindings and all(b['status'] == 'bound' for b in bindings) else
                        'requires_binding' if len(uses) > 1 or declarations else 'request_level' if uses else 'unspecified',
        'bindings': bindings, 'verification_level': 'intent_only', 'geometry_verified': False,
    }
