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
    plan.setdefault('proportion_hints', {})['building_use_intent'] = {
        'schema': 'formacraft.building_use.v1', 'detected_uses': uses,
        'scope_status': 'requires_binding' if len(uses) > 1 else 'request_level' if uses else 'unspecified',
        'verification_level': 'intent_only', 'geometry_verified': False,
    }
