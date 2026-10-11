"""Explicit material and paint controls for an existing single native Chinese hall."""
import re

_NAMES = {'石砖': 'minecraft:stone_bricks', '白色混凝土': 'minecraft:white_concrete',
          '橡木木板': 'minecraft:oak_planks', '云杉木板': 'minecraft:spruce_planks',
          '深板岩瓦': 'minecraft:deepslate_tiles', '红色陶瓦': 'minecraft:red_terracotta',
          '蓝色陶瓦': 'minecraft:blue_terracotta', '绿色陶瓦': 'minecraft:green_terracotta',
          '玻璃': 'minecraft:glass', '橡木原木': 'minecraft:oak_log'}
_ROLES = {'wall_block': '墙体|外墙', 'floor_block': '地板|楼板', 'roof_block': '屋顶|屋面',
          'pillar_block': '柱子|柱材', 'paint_block': '彩画', 'foundation_block': '台基',
          'trim_block': '梁材|梁枋', 'window_block': '窗材', 'bracket_block': '斗拱'}


def apply_chinese_material_contract(plan: dict, text: str) -> None:
    targets = [c for c in plan.get('components', []) if isinstance(c, dict)
               and c.get('component_type') == 'STRUCTURE'
               and (c.get('params', {}).get('typology_id') == 'tailiang_timber_hall'
                    or 'typology:tailiang_timber_hall' in c.get('features', []))]
    if not targets: return
    hits = {}
    names = '|'.join(sorted(_NAMES, key=len, reverse=True))
    material = rf'(?:[a-z0-9_.-]+:[a-z0-9_./-]+|{names})'
    for key, role in _ROLES.items():
        for m in re.finditer(rf'(?:{role})(?:材料)?(?:使用|采用|用)({material})', text):
            if re.search(r'不要|不使用|不采用', text[max(0, m.start()-6):m.start()]): continue
            hits.setdefault(key, []).append((_NAMES.get(m[1], m[1]), m[0]))
    for key, noun in [('includePainting', '彩画'), ('includeBrackets', '斗拱')]:
        for m in re.finditer(rf'(不要|不设|取消|设置|增加)(?:{noun})', text):
            hits.setdefault(key, []).append((m[1] in ('设置', '增加'), m[0]))
    scoped = bool(re.search(r'左栋|右栋|第.+?栋|两座|两栋|建筑群|东侧建筑|西侧建筑', text))
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
