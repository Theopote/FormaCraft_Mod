import json, pathlib, subprocess

new = json.loads(pathlib.Path('src/main/resources/assets/formacraft/generation_capability_catalog.json').read_text(encoding='utf-8'))
old = json.loads(subprocess.run(
    ['git', 'show', 'HEAD:src/main/resources/assets/formacraft/generation_capability_catalog.json'],
    capture_output=True, text=True, encoding='utf-8').stdout)

for k in ['schema', 'version']:
    assert old[k] == new[k], (k, old[k], new[k])
print('schema/version match')

for k in ['hostable_component_types', 'hosted_component_types']:
    assert old[k] == new[k], (k, old[k], new[k])
    print(f'{k} match ({len(new[k])})')

print('typologies: old', len(old['typologies']), '-> new', len(new['typologies']))
print('  old-only (stale hand-written):', [t for t in old['typologies'] if t not in new['typologies']])
print('  new-only (previously missing):', [t for t in new['typologies'] if t not in old['typologies']])

# roof aliases / tokens
print('roof aliases match:', old['roof']['aliases'] == new['roof']['aliases'])
print('roof supported_tokens match:', old['roof']['supported_tokens'] == new['roof']['supported_tokens'],
      len(old['roof']['supported_tokens']), len(new['roof']['supported_tokens']))
print('roof fallback_policy match:', old['roof'].get('fallback_policy') == new['roof'].get('fallback_policy'))
print('building_classes match:', old['building_classes'] == new['building_classes'])

# component_types grew from hand-maintained subset
print('component_types: old', len(old['component_types']), '-> new', len(new['component_types']))
for t, v in old['component_types'].items():
    nv = new['component_types'].get(t)
    if nv != v:
        print('  CHANGED', t, v, '->', nv)
