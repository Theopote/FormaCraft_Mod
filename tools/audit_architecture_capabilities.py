"""Inventory configured architectural rules, without claiming gameplay acceptance."""
import argparse
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'src/main/resources/assets/formacraft'

def read(path):
    return json.loads(path.read_text(encoding='utf-8'))

def audit():
    profiles = read(ASSETS/'style_profiles/style_profile_catalog_v1.json')['profiles']
    palettes = read(ASSETS/'palettes/palette_catalog_v1.json')['palettes']
    typologies = read(ASSETS/'structural_typologies/structural_typologies_v1.json')['typologies']
    cultures = [read(path) for path in sorted((ASSETS/'culture_cards').glob('*.json'))]
    identities=read(ASSETS/'style_profiles/style_identity_catalog_v1.json')
    def canonical(value):
        return identities['aliases'].get(value,value)
    errors, warnings, styles = [], [], []
    for alias,target in identities['aliases'].items():
        if target not in profiles: errors.append(f'{alias}: missing canonical style {target}')
    for target in identities['lexical_aliases']:
        if target not in profiles: errors.append(f'{target}: lexical aliases lack canonical profile')
    type_ids={item['id'] for item in typologies}
    for identity,variant in identities['variants'].items():
        if variant.get('structural_typology') and variant['structural_typology'] not in type_ids:
            errors.append(f'{identity}: missing structural typology')
    for identity, value in profiles.items():
        defaults = value.get('defaults', {})
        palette = defaults.get('components', {}).get('palette_id')
        if palette and palette not in palettes: errors.append(f'{identity}: missing palette {palette}')
        styles.append({'id': identity, 'family': value.get('meta', {}).get('family'),
                       'rule_sections': sorted(defaults), 'palette_id': palette,
                       'allowed_archetypes': value.get('constraints', {}).get('allowed_archetypes', []),
                       'culture_cards': [c['id'] for c in cultures if canonical(c.get('styleId')) == identity],
                       'evidence': 'configured_rules', 'gameplay_acceptance': 'not_assessed'})
    for card in cultures:
        if card.get('styleId') and canonical(card['styleId']) not in profiles \
                and card['styleId'] not in identities['variants'] and card['styleId'] not in identities['non_style_ids']:
            warnings.append(f"culture {card['id']}: styleId outside canonical catalog: {card['styleId']}")
    interpreters = ROOT/'src/main/java/com/formacraft/server/generation/typology/interpreter'
    initializer = (ROOT/'src/main/java/com/formacraft/server/init/TypologySystemInitializer.java').read_text(encoding='utf-8')
    types = []
    for item in typologies:
        identity = item.get('interpreterId')
        sources = []
        for path in interpreters.glob('*.java'):
            source=path.read_text(encoding='utf-8')
            direct=re.search(r'typologyId\(\)\s*\{\s*return\s+"([^"]+)"',source)
            delegated=re.search(r'typologyId\(\)\s*\{\s*return\s+(\w+)\.TYPOLOGY_ID',source)
            resolved=direct.group(1) if direct else None
            if delegated:
                builder=interpreters.parent/'builder'/f'{delegated.group(1)}.java'
                if builder.exists():
                    constant=re.search(r'TYPOLOGY_ID\s*=\s*"([^"]+)"',builder.read_text(encoding='utf-8'))
                    resolved=constant.group(1) if constant else None
            if resolved==identity: sources.append(path)
        registered = any(f'new {path.stem}(' in initializer for path in sources)
        if item.get('routingPolicy') == 'typology_first' and not registered:
            errors.append(f"{item['id']}: no built-in interpreter registration found")
        types.append({'id': item['id'], 'name': item.get('displayName'),
                      'style_families': item.get('styleFamilies', []),
                      'routing_policy': item.get('routingPolicy'), 'interpreter_id': identity,
                      'registered_source': [str(p.relative_to(ROOT)).replace('\\','/') for p in sources],
                      'evidence': 'registered_interpreter' if registered else 'catalog_only',
                      'gameplay_acceptance': 'not_assessed'})
    return {'schema': 'formacraft.architecture_capability_audit.v1', 'styles': styles,
            'style_aliases':identities['aliases'], 'recognized_variants':identities['variants'],
            'non_style_ids':identities['non_style_ids'],
            'structural_types': types, 'culture_card_count': len(cultures),
            'errors': errors, 'warnings': warnings,
            'limits': ['Configured styles do not certify generated geometry.',
                       'Purpose-specific functional room coverage is not assessed.',
                       'Recognized variants retain identity but do not yet have dedicated style profile rules.']}

def markdown(report):
    lines = ['# 建筑风格与结构类型能力盘点', '',
             '由 `python tools/audit_architecture_capabilities.py` 从当前目录生成。配置和注册证据不等于游戏效果验收。', '',
             '## 风格目录', '', '| 风格 ID | 材料目录 | 文化卡片数 | 证据 |', '|---|---|---:|---|']
    for s in report['styles']:
        lines.append(f"| {s['id']} | {s['palette_id'] or '未配置'} | {len(s['culture_cards'])} | 已配置规则，未判定游戏验收 |")
    lines += ['', '## 结构类型', '', '| 类型 | 生成路径 | 证据 |', '|---|---|---|']
    for t in report['structural_types']:
        lines.append(f"| {t['id']} | {t['routing_policy']} | {t['evidence']} |")
    lines += ['', '## 等价别名', ''] + [f'- {alias} → {target}' for alias,target in report['style_aliases'].items()]
    lines += ['', '## 已识别但尚无独立风格规则的变体', '']
    lines += [f"- {identity}：{value['kind']}；保留身份，不冒充已完成生成能力。" for identity,value in report['recognized_variants'].items()]
    lines += ['', '## 非风格标识', ''] + ['- '+identity for identity in report['non_style_ids']]
    lines += ['', '## 待统一的文化风格标识', ''] + (['- '+w for w in report['warnings']] or ['目录外标识均已明确分类；变体几何规则仍待完善。'])
    lines += ['', '## 配置错误', ''] + (['- '+e for e in report['errors']] or ['未发现缺失材料目录或未注册的专用解释器。'])
    return '\n'.join(lines)+'\n'

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true', help='Validate without writing reports')
    args = parser.parse_args()
    report = audit()
    if not args.check:
        target=ROOT/'docs/architecture';target.mkdir(parents=True, exist_ok=True)
        (target/'CAPABILITY_INVENTORY.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n',encoding='utf-8')
        (target/'CAPABILITY_INVENTORY.md').write_text(markdown(report),encoding='utf-8')
    print(json.dumps({'styles':len(report['styles']), 'structural_types':len(report['structural_types']),
                      'errors':report['errors'], 'warnings':len(report['warnings'])},ensure_ascii=False))
    raise SystemExit(bool(report['errors']))
