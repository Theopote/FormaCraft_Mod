"""Explicit material and paint controls for an existing single native Chinese hall."""
import re
from .material_alias_registry import material_aliases
from .ornament_contract import (get_ornament_contract, ornament_targets,
                                collect_ornament_hits, has_unresolved_ornament_scope)

_ROLES = {'wall_block': '墙体|外墙', 'floor_block': '地板|楼板', 'roof_block': '屋顶|屋面',
          'pillar_block': '柱子|柱材', 'paint_block': '彩画', 'foundation_block': '台基',
          'trim_block': '梁材|梁枋', 'window_block': '窗材', 'bracket_block': '斗拱'}


def apply_chinese_material_contract(plan: dict, text: str) -> None:
    definition = get_ornament_contract('chinese_timber')
    targets = ornament_targets(plan, definition)
    if not targets: return
    aliases = material_aliases()
    hits = {}
    names = '|'.join(re.escape(name) for name in sorted(aliases, key=len, reverse=True))
    material = rf'(?:[a-z0-9_.-]+:[a-z0-9_./-]+|{names})'
    for key, role in _ROLES.items():
        for m in re.finditer(rf'(?:{role})(?:材料)?(?:使用|采用|用)({material})', text):
            if re.search(r'(?:不要|不使用|不采用)\s*$', text[max(0, m.start()-8):m.start()]): continue
            hits.setdefault(key, []).append((aliases.get(m[1], m[1]), m[0]))
    for key, entries in collect_ornament_hits(text, definition).items():
        hits.setdefault(key, []).extend(entries)
    scoped = has_unresolved_ornament_scope(text, definition)
    report, updates = [], {}
    for key, entries in hits.items():
        unique = {value for value, _ in entries}
        status = 'conflicting_values' if len(unique)!=1 else 'planned'
        if status=='planned' and (scoped or len(targets)!=1): status='unresolved_building_scope'
        value = next(iter(unique)) if len(unique)==1 else None
        if status=='planned': updates[key]=value
        report.append({'property': key, 'value': value, 'status': status,
                       'source_text': [source for _, source in entries]})
    if any(r['status']!='planned' for r in report):
        if not plan.get('capability_gap'):
            plan['plan_status']='capability_gap'
            plan['capability_gap']={'code': 'E_CHINESE_MATERIAL_CONTRACT',
                'message': '中式材料或彩画要求冲突，或建筑归属不明确',
                'path': 'proportion_hints.chinese_material_contract',
                'suggestions': [r['property']+': '+r['status'] for r in report if r['status']!='planned']}
        for r in report:
            if r['status']=='planned': r['status']='not_applied_due_to_conflict'
    elif len(targets)==1:
        params=targets[0].setdefault('params', {})
        params.update(updates)
        if report and (plan.get('capability_gap') or {}).get('code')=='E_CHINESE_MATERIAL_CONTRACT':
            plan.pop('capability_gap', None); plan.pop('plan_status', None)
        for r in report: r['target_components']=[params.get('component_id')]
    plan.setdefault('proportion_hints', {})['chinese_material_contract']={
        'schema': 'formacraft.chinese_material.v1', 'requirements': report,
        'verification_level': 'plan_parameters_only', 'material_validation': 'runtime_block_registry'}
