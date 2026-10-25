"""Rebuild the authored D/E fixtures and linked style assets deterministically."""
import copy
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'src/main/resources/assets/formacraft'
FIXTURES = ROOT / 'python_backend/eval/fixtures'

# id, Chinese name, English alias, family, wall, roof, trim, roof vocabulary, pattern
STYLES = [
    ('Desert_Adobe','沙漠泥砖','desert adobe','D','mud_bricks','smooth_sandstone','packed_mud','flat','smooth'),
    ('Prairie_Earth','草原土坯','prairie earth house','D','packed_mud','mud_bricks','oak_log','flat','smooth'),
    ('Arctic_Igloo','冰屋','arctic igloo','D','snow_block','snow_block','packed_ice','dome','smooth'),
    ('Stilt_Timber','吊脚木楼','stilt timber house','D','spruce_planks','dark_oak_planks','spruce_log','gable','timber_frame'),
    ('Water_Platform','水上木屋','water platform house','D','oak_planks','spruce_planks','oak_log','gable','timber_frame'),
    ('Xianxia_Island','仙侠悬浮岛','xianxia floating island','E','quartz_block','dark_prismarine','crimson_planks','hip','smooth'),
    ('Dwarven_Hall','矮人石殿','dwarven stone hall','E','polished_deepslate','deepslate_tiles','chiseled_stone_bricks','barrel_vault','smooth'),
    ('Elven_Treehouse','精灵树城','elven forest city','E','birch_planks','moss_block','stripped_birch_log','cone','timber_frame'),
    ('Cyberpunk','赛博朋克','cyberpunk block','E','black_concrete','gray_concrete','cyan_concrete','flat','neon_bands'),
    ('Steampunk','蒸汽朋克','steampunk hall','E','bricks','cut_copper','waxed_cut_copper','gambrel','timber_frame'),
    ('Dark_Fortress','暗黑魔物堡','dark fantasy fortress','E','blackstone','polished_blackstone','crying_obsidian','gable','smooth'),
]

VISUAL_FEATURES = {
    'Desert_Adobe': [['adobe walls','泥砖墙'],['small shaded openings','小型遮阳窗'],['flat earthen roof','泥土平顶']],
    'Prairie_Earth': [['packed earth walls','夯土墙'],['low earthen volume','低矮土坯体量'],['flat mud roof','土坯平顶']],
    'Arctic_Igloo': [['snow dome','积雪穹顶'],['ice entrance','冰屋入口'],['circular snow walls','圆形雪墙']],
    'Stilt_Timber': [['exposed timber stilts','外露木支柱'],['raised timber deck','架空木平台'],['gable timber hall','双坡木楼']],
    'Water_Platform': [['water platform','水上平台'],['raised timber deck','架空木平台'],['waterside timber posts','临水木支柱']],
    'Xianxia_Island': [['floating inverted island','倒锥悬浮岛'],['flying eaves','夸张飞檐'],['island pavilion','岛上楼阁']],
    'Dwarven_Hall': [['massive stone hall','巨型石殿'],['barrel stone vault','石质筒拱'],['brazier columns','火盆列']],
    'Elven_Treehouse': [['giant tree trunk','巨型树干'],['tree canopy roof','树冠屋顶'],['elevated tree deck','树上平台']],
    'Cyberpunk': [['black concrete facade','黑色混凝土立面'],['neon light bands','霓虹灯带'],['offset rooftop pods','错位屋顶舱']],
    'Steampunk': [['copper roof','铜屋顶'],['industrial chimney','工业烟囱'],['brick workshop','红砖工坊']],
    'Dark_Fortress': [['blackstone fortress','黑石堡垒'],['sharp stone spikes','尖锐石刺'],['obsidian trim','黑曜石饰边']],
}


def load(path):
    return json.loads(path.read_text(encoding='utf-8'))


def save(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')


def primitive(name, x,y,z,w,d,h,kind,block,**extra):
    return dict(component_type='PRIMITIVE',relative_position=dict(x=x,y=y,z=z),
                dimensions=dict(width=w,depth=d,height=h),features=[],
                params=dict(component_id=name,anchor_mode='min_corner',kind=kind,material='minecraft:'+block,**extra))


def add_dark_fortress_towers(plan):
    """Two hollow defensive tower shells; occupied tower circulation is not claimed."""
    body=plan['components'][0]; size=body['dimensions']['width']; depth=body['dimensions']['depth']
    plan['components']=[c for c in plan['components'] if not c.get('params',{}).get('component_id','').startswith('guard_tower_')]
    for i,x in enumerate((-5,size)):
        plan['components'].append(dict(component_type='TOWER',relative_position=dict(x=x,y=0,z=depth-6),
            dimensions=dict(width=5,depth=5,height=14),features=[],params=dict(component_id=f'guard_tower_{i}',
            anchor_mode='min_corner',wall_block='minecraft:blackstone',floor_block='minecraft:stone_bricks',
            hollow=True,wall_thickness=1,floor_count=1)))


def add_raised_access(plan):
    """Fixed-site examples: authored elevation, not automatic water/terrain fitting."""
    body = plan['components'][0]
    width = body['dimensions']['width']
    center = width // 2
    wall = body['params']['wall_block'].removeprefix('minecraft:')
    plan['global_constraints']['terrain_strategy'] = 'PRESERVE'
    body['features'] = list(dict.fromkeys([*body.get('features', []), 'entrance']))
    plan['components'] = [c for c in plan['components']
                          if not c['params'].get('component_id', '').startswith('access_')]
    if plan['style_profile'] == 'Water_Platform':
        # A level bank at deck elevation is an explicit fixture assumption.
        plan['components'].append(primitive('access_walkway', center-1,5,-8,3,8,1,'box',wall))
        trim = body['params']['pillar_block'].removeprefix('minecraft:')
        for i, x in enumerate((center-1, center+1)):
            plan['components'].append(primitive('access_post_'+str(i),x,0,-7,1,1,5,'box',trim))
    else:
        plan['components'].append(primitive('access_landing',center-1,5,-3,3,3,1,'box',wall))
        for step in range(5):
            plan['components'].append(primitive('access_step_'+str(step),center-1,step,-8+step,3,1,1,'box',wall))
        if width == 17:
            # The diversity alternative has a supported covered porch, not just a resized shell.
            trim = body['params']['pillar_block'].removeprefix('minecraft:')
            plan['components'].append(primitive('access_canopy',center-3,9,-3,7,3,1,'box',wall))
            for i, x in enumerate((center-3, center+3)):
                plan['components'].append(primitive('access_canopy_post_'+str(i),x,0,-2,1,1,9,'box',trim))


def add_treehouse_access(plan):
    """Single supported treehouse with an authored straight timber approach."""
    body = plan['components'][0]
    center = body['dimensions']['width'] // 2
    elevation = body['relative_position']['y']
    plan['global_constraints']['terrain_strategy'] = 'PRESERVE'
    body['features'] = list(dict.fromkeys([*body.get('features', []), 'entrance']))
    plan['components'] = [c for c in plan['components']
                          if not c['params'].get('component_id', '').startswith('tree_access_')
                          and c['params'].get('component_id') != 'tree_entrance']
    plan['components'].append(dict(component_type='ENTRANCE',
        relative_position=dict(x=center-2,y=elevation+1,z=0),
        dimensions=dict(width=5,depth=1,height=4),features=['entrance'],
        params=dict(component_id='tree_entrance',host_id=body['params']['component_id'],
                    anchor_mode='min_corner',facing='SOUTH',door_width=3,door_height=3,canopy_depth=0)))
    # Five structural columns leave three clear walking columns between guards.
    plan['components'].append(primitive('tree_access_landing',center-2,elevation-1,-3,
                                        5,3,2,'box','birch_planks'))
    for side, x in enumerate((center-2,center+2)):
        plan['components'].append(primitive('tree_access_guard_landing_'+str(side),x,elevation+1,-3,
                                            1,3,2,'box','stripped_birch_log'))
    for step in range(elevation):
        # Two-course treads share vertical faces, forming a continuous wooden run.
        bottom = max(0, step-1)
        z = -elevation-3+step
        plan['components'].append(primitive('tree_access_step_'+str(step),center-2,bottom,
                                            z,5,1,step-bottom+1,'box','birch_planks'))
        for side, x in enumerate((center-2,center+2)):
            plan['components'].append(primitive(f'tree_access_guard_{step}_{side}',x,step+1,z,
                                                1,1,2,'box','stripped_birch_log'))


def main():
    manifest_path=ASSETS/'style_profiles/style_pack_manifest_v1.json'
    manifest=load(manifest_path)
    identities=load(ASSETS/'style_profiles/style_identity_catalog_v1.json')
    profiles=load(ASSETS/'style_profiles/style_profile_catalog_v1.json')
    palettes=load(ASSETS/'palettes/palette_catalog_v1.json')
    scenarios=load(FIXTURES/'scenarios.json')
    typologies=load(ASSETS/'structural_typologies/structural_typologies_v1.json')
    template=load(FIXTURES/'plans/alpine_chalet_compact.json')
    for sid,cn,en,family,wall,roof,trim,roof_type,pattern in STYLES:
        gid={'Cyberpunk':'cyberpunk_block','Steampunk':'steampunk_hall'}.get(sid,sid.lower())
        aliases=list(dict.fromkeys([cn,en,gid,*identities['lexical_aliases'].get(sid,[])]))
        material=lambda value:'minecraft:'+value
        palette=dict(wall=material(wall),roof=material(roof),floor='minecraft:stone_bricks',
            window='minecraft:glass_pane',foundation='minecraft:stone_bricks',trim=material(trim),
            pillar=material(trim),cap='minecraft:stone_brick_slab',door='minecraft:oak_door',
            roof_trim=material(trim),stairs='minecraft:stone_brick_stairs',roof_slab='minecraft:stone_brick_slab')
        ratio=.08 if family=='D' else .22
        base=dict(wall_block=palette['wall'],roof_block=palette['roof'],floor_block=palette['floor'],
                  window_block=palette['window'],foundation_block=palette['foundation'],trim_block=palette['trim'],
                  pillar_block=palette['pillar'],roof_type=roof_type,window_style='pane',window_ratio=ratio,
                  facade_profile='none',wall_pattern=pattern,floor_height=4)
        if pattern=='neon_bands':
            base.update(emissive_enabled=True,emissive_block='minecraft:sea_lantern')
        profile=dict(meta=dict(display_name=cn,family=family,tags=aliases,description='Authored original voxel style; not a historical reconstruction.'),
            defaults=dict(genome=dict(culturalStyle=dict(region='fantasy' if family=='E' else 'vernacular',era=gid)),
                geometry=dict(symmetry='mirror',roof=dict(type=roof_type)),materials={k:[v] for k,v in palette.items()},
                components=dict(palette_id='PALETTE_'+sid.upper(),facade_profile='none'),
                component_params={kind:copy.deepcopy(base) for kind in ['MASS_MAIN','MASS_SECONDARY','MASS_WING','SIDE_WING']}),
            constraints=dict(terrain_policy='local_level',symmetry_preference='mirror',silhouette_strength='high'))
        profile['defaults']['component_params']['ROOF']=dict(roof_type=roof_type,overhang=0,roof_block=palette['roof'])
        profile['defaults']['component_params']['FACADE_WINDOWS']=dict(window_style='pane',window_ratio=ratio,window_block=palette['window'])
        profiles['profiles'][sid]=profile
        identities['lexical_aliases'][sid]=aliases
        save(ASSETS/'style_genomes'/f'{gid}.json',dict(id=gid,name=en,style=sid,palette=palette,
            params=dict(roofType=roof_type,windowRatio=ratio,windowStyle='pane',wallPattern=pattern,floorHeight=4,preferSymmetry=True)))
        palette_id='PALETTE_'+sid.upper()
        parts={'WALL_BASE':'wall','WALL':'wall','WALL_FOUNDATION':'foundation','FLOOR':'floor','ROOF':'roof',
               'ROOF_SURFACE':'roof','WINDOW':'window','FRAME':'pillar','PILLAR':'pillar','WALL_ACCENT':'trim','TRIM':'trim','DOOR':'door'}
        palettes['palettes'][palette_id]=dict(meta=dict(display_name=cn,tags=aliases),parts={p:[dict(id=palette[r],weight=100)] for p,r in parts.items()})
        typology='flat_roof_pavilion' if roof_type=='flat' else 'timber_gable_house'
        names=[]
        for size in ((13,21,17) if sid in ('Stilt_Timber','Cyberpunk') else (13,21)):
            plan=copy.deepcopy(template)
            plan['style_profile']=sid
            plan['style_attributes']=dict(wall_material=palette['wall'],roof_material=palette['roof'],floor_material=palette['floor'])
            plan['proportion_hints']=dict(typology=typology,proportion_card_id=gid,height_to_width=8/size,depth_to_width=(size+4)/size,window_wall_ratio=ratio)
            body,top=plan['components'][:2]
            elevation=21 if sid=='Xianxia_Island' else 13 if sid=='Elven_Treehouse' else 5 if sid in ('Stilt_Timber','Water_Platform') else 0
            body_height=12 if size==17 else 8
            body.update(relative_position=dict(x=0,y=elevation,z=0),dimensions=dict(width=size,depth=size+4,height=body_height))
            body['params']={**base,'component_id':gid,'anchor_mode':'min_corner','roof_type':'none','floor_count':2,'hollow':True}
            if sid=='Arctic_Igloo':
                body['dimensions']['height']=4
                body['params'].update(shape='circle',plan_type='none',floor_count=1,floor_height=3,window_ratio=0)
            top.update(relative_position=dict(x=0,y=elevation+body_height-1,z=0),dimensions=dict(width=size,depth=size+4,height=3 if roof_type=='flat' else 10 if roof_type=='barrel_vault' else 8))
            top['params']=dict(roof_type=roof_type,overhang=0,roof_block=palette['roof'],component_id=gid+'_roof',host_id=gid,anchor_mode='min_corner')
            if sid=='Arctic_Igloo':
                top['relative_position']['y']=3
            plan['components']=[body,top]
            if sid in ('Stilt_Timber','Water_Platform'):
                for i,(x,z) in enumerate(((1,1),(size-2,1),(1,size+2),(size-2,size+2))):
                    plan['components'].append(primitive('post_'+str(i),x,0,z,1,1,5,'box',trim))
                add_raised_access(plan)
            if sid=='Xianxia_Island':
                plan['components'].append(primitive('island',-3,12,-3,size+6,size+10,9,'floating_mass','stone'))
            if sid=='Elven_Treehouse':
                plan['components'].append(primitive('tree_trunk',size//2-2,0,size//2-2,5,5,13,'cylinder','oak_log',radius=2.5))
                add_treehouse_access(plan)
            if sid=='Dwarven_Hall':
                for i,x in enumerate((1,size-2)):
                    plan['components'].append(primitive('brazier_'+str(i),x,1,1,1,1,2,'box','shroomlight'))
            if sid=='Steampunk':
                plan['components'].append(primitive('chimney',size+1,0,2,3,3,18,'cylinder','bricks',radius=1.5))
                plan['components'].append(primitive('gear',size+5,4,0,7,3,7,'cylinder','waxed_cut_copper',radius=3.5,rotation_x_deg=90,hollow=True))
                for i,(x,y,w,h) in enumerate(((size+4,6,2,3),(size+11,6,2,3),(size+7,3,3,2),(size+7,10,3,2))):
                    plan['components'].append(primitive('gear_tooth_'+str(i),x,y,1,w,1,h,'box','waxed_cut_copper'))
            if sid=='Dark_Fortress':
                for i,x in enumerate((-3,size)):
                    plan['components'].append(primitive('spike_'+str(i),x,0,1,3,3,19,'cone','blackstone',radius=1.5))
                add_dark_fortress_towers(plan)
            if sid=='Cyberpunk':
                plan['components'].append(primitive('offset_roof_pod',size-5,body_height+2,2,5,5,5,'box','black_concrete'))
            name=f'p2_{gid}_{size}.json'; names.append(name)
            save(FIXTURES/'plans'/name,plan)
            save(ASSETS/'llmplan_examples'/name,dict(id=Path(name).stem,prompt='build a '+en,plan=plan))
            scenarios=[s for s in scenarios if s['id']!=Path(name).stem]
            scenarios.append(dict(id=Path(name).stem,prompt='build a '+en,plan_fixture='plans/'+name,ci=True,tags=['p2',family,gid]))
        entry=dict(style_id=sid,genome_id=gid,culture_card_id=gid,proportion_card_id=gid,typology_id=typology,
                   palette_id=palette_id,family=family,aliases=aliases,prompts=['生成一座'+cn,'build a '+en],
                   negative_prompt='不要'+cn,plans=names[:2],roof_type=roof_type,expected_wall=palette['wall'],expected_roof=palette['roof'],expected_window=palette['window'])
        if len(names)>2: entry['diversity_plans']=names[2:]
        previous = next((s for s in manifest['styles'] if s['style_id']==sid), {})
        if previous.get('variant_cards'):
            entry['variant_cards'] = copy.deepcopy(previous['variant_cards'])
        manifest['styles']=[s for s in manifest['styles'] if s['style_id']!=sid]+[entry]
        for definition in typologies['typologies']:
            if definition['id']==typology:
                for key,value in [('cultureCardIds',gid),('proportionCardIds',gid)]:
                    if value not in definition.setdefault(key,[]): definition[key].append(value)
        site_constraints = ([
            'Fixed-site example: pile bases are anchor+0, deck floor is anchor+5; use terrain_strategy=PRESERVE to retain authored geometry.',
            'Water example assumes a bed at anchor-1, water no higher than anchor+3 and a bank at deck elevation; deeper or uneven sites require separate fitting.',
            'Use the authored access components: steps for Stilt_Timber, elevated bank walkway for Water_Platform; do not infer an access route from style alone.'
        ] if sid in ('Stilt_Timber','Water_Platform') else [])
        if sid == 'Elven_Treehouse':
            site_constraints = [
                'Single treehouse: trunk base is anchor+0, floor is anchor+13, with an authored straight timber approach on negative z.',
                'Use terrain_strategy=PRESERVE for this fixed-site composition; do not claim a spiral stair, canopy bridge network or populated tree city.'
            ]
        save(ASSETS/'culture_cards'/f'{gid}.json',dict(id=gid,styleId=sid,intents=['STYLE_PRESET'],keywords=aliases,synonyms={},
             keywordWeights={a:12 for a in aliases},negativeKeywords=['不要'+cn,'not '+en],proportionCardId=gid,
             structuralTypologyId=typology,llmPlanExampleRefs=names,exampleRefs=[],archetypes=[dict(name=gid,constraints=[
                 'Use style_profile='+sid+'; original fictional/vernacular composition, not a named landmark.',
                 'Use roof_type='+roof_type+', wall_pattern='+pattern+'; explicit user requirements take priority.',
                 'See authored plan examples for supports, raised islands, trunks, chimneys or spikes; these are compositional components.', *site_constraints])]))
        save(ASSETS/'proportion_cards'/f'{gid}.json',dict(id=gid,typology=typology,matchKeywords=aliases,
             ratios=dict(height_to_width=dict(min=.2,ideal=.6,max=2),depth_to_width=dict(min=.5,ideal=1,max=1.6),
                         window_wall_ratio=dict(min=0,ideal=ratio,max=.5)),openingGrammar=dict(window_aspect=['rectangular']),
             aiInstruction='Authored voxel approximation. Preserve requested elevation, material and explicit openings.'))
    save(manifest_path,manifest)
    save(ASSETS/'style_profiles/style_identity_catalog_v1.json',identities)
    save(ASSETS/'style_profiles/style_profile_catalog_v1.json',profiles)
    save(ASSETS/'palettes/palette_catalog_v1.json',palettes)
    save(FIXTURES/'scenarios.json',scenarios)
    save(ASSETS/'structural_typologies/structural_typologies_v1.json',typologies)
    diversity_path=FIXTURES/'diversity_scenarios.json'
    diversity=load(diversity_path)
    for family,gid,prompt in [('D','stilt_timber','build a stilt timber house'),('E','cyberpunk_block','build a cyberpunk block')]:
        scenario_id=f'style_family_{family}_diversity'
        diversity=[s for s in diversity if s['id']!=scenario_id]
        diversity.append(dict(id=scenario_id,prompt=prompt,plan_fixtures=[f'plans/p2_{gid}_{size}.json' for size in (13,21,17)],
            thresholds=dict(min_samples=3,min_unique_ratio=1.0,min_mean_distance=.12,min_routing_unique=1,require_routing_diversity=False),
            ci=True,notes=['Authored size and envelope alternatives for one style request; not live model samples.']))
    save(diversity_path,diversity)
    visual_path=ASSETS/'style_visual_cases/catalog_v1.json'
    visual=load(visual_path)
    for entry in manifest['styles']:
        if entry['style_id'] not in VISUAL_FEATURES: continue
        case_id=entry['genome_id']
        visual['cases']=[case for case in visual['cases'] if case['id']!=case_id]
        visual['cases'].append(dict(id=case_id,style_id=entry['style_id'],plan_fixture=entry['plans'][0],
            feature_groups={f'feature_{index}':terms for index,terms in enumerate(VISUAL_FEATURES[entry['style_id']])}))
    save(visual_path,visual)


if __name__=='__main__':
    main()
