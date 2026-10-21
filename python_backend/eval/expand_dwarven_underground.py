"""Author fixed-site underground variants without replacing above-ground examples."""
from copy import deepcopy
import math
from .expand_p2_assets import ASSETS, FIXTURES, load, save, primitive


def underground_plan(base):
    plan = deepcopy(base)
    body, roof = plan['components'][:2]
    width, depth = body['dimensions']['width'], body['dimensions']['depth']
    center = width // 2
    plan['global_constraints']['terrain_strategy'] = 'PRESERVE'
    body['relative_position']['y'] = -20
    body['dimensions']['height'] = 10
    body['features'] = ['entrance']
    body['params'].update(floor_count=1, floor_height=9, window_ratio=0,
                          suppress_windows=True, plan_type='none', shape='rectangle')
    roof['relative_position']['y'] = -11
    roof['dimensions']['height'] = 6
    roof['params'].update(roof_height=6, wall_block='minecraft:polished_deepslate')
    plan['proportion_hints'].update(height_to_width=10/width, window_wall_ratio=0)
    for light in plan['components'][2:]:
        light['relative_position']['y'] -= 20
    plan['components'].append(dict(component_type='ENTRANCE',
        relative_position=dict(x=center-2,y=-19,z=0),
        dimensions=dict(width=5,depth=1,height=4),features=['entrance'],
        params=dict(component_id='underground_entrance',host_id='dwarven_hall',
                    anchor_mode='min_corner',facing='SOUTH',door_width=3,door_height=3,canopy_depth=0)))
    # Keep the quantized vault lining and slope risers intact. Air strips stay
    # below the lowest neighboring roof sample rather than cutting the shell.
    profile = [int(math.floor(math.sqrt(max(0, 1-(1-min(x,width-1-x)/(width//2))**2))*5+.5))
               for x in range(width)]
    for x in range(1,width-1):
        height = min(profile[x-1:x+2])
        if height:
            plan['components'].append(primitive(f'vault_excavation_{x}',x,-11,1,1,depth-2,height,'box','air',
                                                host_id='dwarven_hall',allow_patch_overwrite=True))
    for step in range(20):
        top, z = -step, -23+step
        plan['components'].append(primitive(f'approach_excavation_{step}',center-1,top+1,z,3,1,3,'box','air'))
        plan['components'].append(primitive(f'underground_tread_{step}',center-1,top-1,z,3,1,2,'box','stone_bricks'))
    plan['components'].append(primitive('underground_landing',center-1,-20,-3,3,3,1,'box','stone_bricks'))
    # The landing itself must be dug through existing rock as well.
    plan['components'].append(primitive('approach_excavation_landing',center-1,-19,-3,3,3,3,'box','air'))
    return plan


def main():
    identity_path=ASSETS/'style_profiles/style_identity_catalog_v1.json'; identity=load(identity_path)
    aliases=identity['lexical_aliases']['Dwarven_Hall']
    for alias in ('地下矮人石殿','矮人地下大厅','underground dwarven hall'):
        if alias not in aliases: aliases.append(alias)
    save(identity_path,identity)
    names=[]
    scenarios=load(FIXTURES/'scenarios.json')
    for width in (13,21):
        name=f'dwarven_underground_{width}.json'; names.append(name)
        plan=underground_plan(load(FIXTURES/'plans'/f'p2_dwarven_hall_{width}.json'))
        save(FIXTURES/'plans'/name,plan)
        save(ASSETS/'llmplan_examples'/name,dict(description='Fixed-site underground vaulted hall with an excavated approach.',
             promptHints=['地下矮人石殿','underground dwarven hall'],plan=plan))
        scenarios=[s for s in scenarios if s['id']!=name[:-5]]
        scenarios.append(dict(id=name[:-5],prompt='build an underground dwarven hall',plan_fixture='plans/'+name,ci=True,
                              tags=['E','underground','excavation']))
    save(FIXTURES/'scenarios.json',scenarios)
    save(ASSETS/'culture_cards/dwarven_underground_hall.json',dict(id='dwarven_underground_hall',styleId='Dwarven_Hall',
         intents=['UNDERGROUND_HALL'],keywords=['地下矮人石殿','矮人地下大厅','underground dwarven hall'],synonyms={},
         keywordWeights={'地下矮人石殿':30,'矮人地下大厅':30,'underground dwarven hall':30},
         negativeKeywords=['不要矮人石殿','not dwarven stone hall'],proportionCardId='dwarven_hall',
         structuralTypologyId='timber_gable_house',llmPlanExampleRefs=names,exampleRefs=[],
         archetypes=[dict(name='underground_vaulted_hall',constraints=[
             'Fixed-site example: surface at anchor+0, hall floor at anchor-20, vault below anchor-5; terrain_strategy=PRESERVE.',
             'Explicit air primitives excavate vault and approach. Hollow/CSG omission alone does not remove existing rock.',
             'Use a dry stone reference site. Water, lava, protected terrain and arbitrary cave/ore networks require separate acceptance.'
         ])]))
    manifest_path=ASSETS/'style_profiles/style_pack_manifest_v1.json'; manifest=load(manifest_path)
    style=next(s for s in manifest['styles'] if s['style_id']=='Dwarven_Hall')
    variants=[v for v in style.get('variant_cards',[]) if v['culture_card_id']!='dwarven_underground_hall']
    variants.append(dict(culture_card_id='dwarven_underground_hall',proportion_card_id='dwarven_hall',
                         typology_id='timber_gable_house',prompts=['生成一座地下矮人石殿','build an underground dwarven hall'],
                         negative_prompt='不要矮人石殿',plans=names))
    style['variant_cards']=variants;save(manifest_path,manifest)
    path=ASSETS/'structural_typologies/structural_typologies_v1.json'; catalog=load(path)
    typology=next(t for t in catalog['typologies'] if t['id']=='timber_gable_house')
    if 'dwarven_underground_hall' not in typology['cultureCardIds']:
        typology['cultureCardIds'].append('dwarven_underground_hall')
    save(path,catalog)


if __name__=='__main__':
    main()
