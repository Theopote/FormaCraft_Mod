"""Author bounded Jiangnan, Romanesque and Tudor voxel interpretations."""
import copy
from .expand_p2_assets import ASSETS, FIXTURES, load, save, primitive

STYLES = [
    ('Chinese_Vernacular_Jiangnan_WaterTown', 'jiangnan_quay', '江南水乡', 'jiangnan water town', 'A', 'white_terracotta', 'deepslate_tiles', 'dark_oak_log', 'smooth'),
    ('Romanesque', 'romanesque_hall', '罗马风', 'romanesque hall', 'B', 'stone_bricks', 'deepslate_tiles', 'chiseled_stone_bricks', 'smooth'),
    ('Tudor', 'tudor_house', '都铎', 'tudor house', 'B', 'white_terracotta', 'dark_oak_planks', 'dark_oak_log', 'timber_frame'),
]


def main():
    archetypes_path = ASSETS/'archetypes/archetypes_v1.json'
    archetypes_text = archetypes_path.read_text(encoding='utf-8')
    archetypes_text = archetypes_text.replace(
        '"properNounAliases": ["江南水乡", "江南水镇", "jiangnan water town"],\n      "genericAliases": ["水镇", "water town", "canal town"]',
        '"properNounAliases": [],\n      "genericAliases": ["江南水乡", "江南水镇", "jiangnan water town", "水镇", "water town", "canal town"]')
    archetypes_path.write_text(archetypes_text, encoding='utf-8')
    identities = load(ASSETS/'style_profiles/style_identity_catalog_v1.json')
    profiles = load(ASSETS/'style_profiles/style_profile_catalog_v1.json')
    manifest = load(ASSETS/'style_profiles/style_pack_manifest_v1.json')
    palettes = load(ASSETS/'palettes/palette_catalog_v1.json')
    scenarios = load(FIXTURES/'scenarios.json')
    template = load(FIXTURES/'plans/alpine_chalet_compact.json')
    genome_template = load(ASSETS/'style_genomes/alpine_chalet.json')
    for sid, gid, cn, en, family, wall, roof, trim, pattern in STYLES:
        aliases = list(dict.fromkeys([cn, en, gid, *identities['lexical_aliases'].get(sid, [])]))
        identities['lexical_aliases'][sid] = aliases
        palette = copy.deepcopy(genome_template['palette'])
        palette.update(wall='minecraft:'+wall, roof='minecraft:'+roof, trim='minecraft:'+trim,
                       pillar='minecraft:'+trim, roof_trim='minecraft:'+trim,
                       stairs='minecraft:stone_brick_stairs', roof_slab='minecraft:stone_brick_slab')
        base = dict(wall_block=palette['wall'], roof_block=palette['roof'], floor_block=palette['floor'],
                    window_block=palette['window'], foundation_block=palette['foundation'], trim_block=palette['trim'],
                    pillar_block=palette['pillar'], roof_type='none', window_style='pane', window_ratio=.15,
                    wall_pattern=pattern, facade_profile='none', floor_height=4)
        profile = copy.deepcopy(profiles['profiles']['Alpine_Chalet'])
        profile['meta'] = dict(display_name=cn, family=family, tags=aliases,
                               description='Bounded authored voxel interpretation; live and historical review pending.')
        profile['defaults']['materials'] = {key:[value] for key,value in palette.items()}
        pid='PALETTE_'+gid.upper()
        profile['defaults']['components']['palette_id'] = pid
        profile['defaults']['components']['facade_profile'] = 'none'
        profile['defaults']['component_params'] = {kind:copy.deepcopy(base) for kind in ('MASS_MAIN','MASS_SECONDARY','MASS_WING','SIDE_WING')}
        profile['defaults']['component_params']['ROOF'] = dict(roof_type='gable',roof_block=palette['roof'],overhang=1)
        profile['defaults']['component_params']['FACADE_WINDOWS'] = dict(window_style='pane',window_ratio=.15,window_block=palette['window'])
        profile['defaults']['genome'] = dict(culturalStyle=dict(region=family,era=gid))
        profiles['profiles'][sid]=profile
        save(ASSETS/'style_genomes'/f'{gid}.json',dict(id=gid,name=en,style=sid,palette=palette,
             params=dict(roofType='gable',windowRatio=.15,windowStyle='pane',wallPattern=pattern,floorHeight=4)))
        parts={'WALL_BASE':'wall','WALL':'wall','WALL_FOUNDATION':'foundation','FLOOR':'floor','ROOF':'roof','ROOF_SURFACE':'roof',
               'WINDOW':'window','PILLAR':'pillar','FRAME':'pillar','TRIM':'trim','DOOR':'door'}
        palettes['palettes'][pid]=dict(meta=dict(display_name=cn,tags=aliases),parts={key:[dict(id=palette[role],weight=100)] for key,role in parts.items()})
        names=[]
        typology='masonry_palazzo' if sid=='Romanesque' else 'timber_gable_house'
        for size in (17,25):
            plan=copy.deepcopy(template)
            plan['style_profile']=sid
            plan['global_constraints'].update(terrain_strategy='PRESERVE',symmetry='NONE')
            plan['style_attributes']=dict(wall_material=palette['wall'],roof_material=palette['roof'],floor_material=palette['floor'])
            plan['proportion_hints']=dict(typology=typology,proportion_card_id=gid,height_to_width=10/size,window_wall_ratio=.15)
            body,top,entry,windows=plan['components']
            body.update(dimensions=dict(width=size,depth=size+4,height=10),features=['entrance'])
            body['params']={**base,'component_id':gid,'anchor_mode':'min_corner','floor_count':2,'hollow':True}
            top.update(relative_position=dict(x=0,y=9,z=0),dimensions=dict(width=size,depth=size+4,height=7))
            top['params'].update(host_id=gid,component_id=gid+'_roof',roof_block=palette['roof'],overhang=1)
            entry['relative_position']=dict(x=size//2-1,y=1,z=0)
            entry['params'].update(host_id=gid,component_id=gid+'_entry',anchor_mode='min_corner',facing='SOUTH',door_width=3,door_height=3)
            windows['params'].update(host_id=gid,component_id=gid+'_windows',window_per_wall=2,window_block=palette['window'],floor_height=4)
            windows['dimensions']=dict(width=size-2,depth=1,height=10)
            plan['components']=[body,top,entry,windows]
            if sid=='Chinese_Vernacular_Jiangnan_WaterTown':
                # Fixed dry quay at y=0; the channel is reserved space, not a fluid simulation.
                plan['components'].append(primitive('quay_walk',0,0,-5,size,5,1,'box','stone_bricks'))
                for side,x in enumerate((0,size-1)):
                    plan['components'].append(primitive('quay_end_'+str(side),x,-2,-5,1,5,2,'box','cobblestone'))
                plan['components'].append(primitive('quay_water_steps',size//2-1,-1,-6,3,1,1,'box','stone_bricks'))
            elif sid=='Romanesque':
                # A freestanding rounded entrance arcade; no claim of an arched wall-cut solver.
                center=size//2
                for dx in range(-5,6):
                    for y in range(5,11):
                        radius2=dx*dx+(y-5)*(y-5)
                        if 9<=radius2<=25:
                            plan['components'].append(primitive(f'arc_{dx}_{y}',center+dx,y,-4,1,2,1,'box','stone_bricks'))
                for dx in (-5,-4,4,5):
                    plan['components'].append(primitive('arc_pier_'+str(dx),center+dx,0,-4,1,2,5,'box','stone_bricks'))
                plan['components'].append(primitive('arc_walk',center-6,0,-5,13,5,1,'box','stone_bricks'))
            name=f'{gid}_{size}.json'; names.append(name)
            save(FIXTURES/'plans'/name,plan)
            save(ASSETS/'llmplan_examples'/name,dict(id=name[:-5],prompt='build a '+en,plan=plan))
            scenarios=[case for case in scenarios if case['id']!=name[:-5]]
            scenarios.append(dict(id=name[:-5],prompt='build a '+en,plan_fixture='plans/'+name,ci=True,tags=[family,gid,'specialist']))
        entry=dict(style_id=sid,genome_id=gid,culture_card_id=gid,proportion_card_id=gid,typology_id=typology,palette_id=pid,
                   family=family,aliases=aliases,prompts=['生成一座'+cn,'build a '+en],negative_prompt='不要'+cn,plans=names,
                   roof_type='gable',expected_wall=palette['wall'],expected_roof=palette['roof'],expected_window=palette['window'])
        manifest['styles']=[style for style in manifest['styles'] if style['style_id']!=sid]+[entry]
        limits={'Romanesque':'The rounded arcade is freestanding, not an arched wall opening or a full Romanesque church.',
                'Tudor':'Exposed timber framing is supported; jettied upper floors and historical fidelity are unverified.',
                'Chinese_Vernacular_Jiangnan_WaterTown':'Fixed dry quay beside reserved channel space; no automatic water-level fitting, fluid or bridge claim.'}
        save(ASSETS/'culture_cards'/f'{gid}.json',dict(id=gid,styleId=sid,intents=['STYLE_PRESET'],keywords=aliases,synonyms={},
             keywordWeights={alias:18 for alias in aliases},negativeKeywords=['不要'+cn,'not '+en,'without '+en],proportionCardId=gid,
             structuralTypologyId=typology,llmPlanExampleRefs=names,exampleRefs=[],archetypes=[dict(name=gid,constraints=[
                 'Use style_profile='+sid+' and preserve explicit user instructions.',limits[sid],
                 'Use compositional components with host_id; no new native interpreter is claimed.'])]))
        save(ASSETS/'proportion_cards'/f'{gid}.json',dict(id=gid,typology=typology,matchKeywords=aliases,
             ratios=dict(height_to_width=dict(min=.2,ideal=.45,max=1),window_wall_ratio=dict(min=.05,ideal=.15,max=.3)),
             aiInstruction=limits[sid]))
    for path,data in [('style_profiles/style_identity_catalog_v1.json',identities),('style_profiles/style_profile_catalog_v1.json',profiles),
                      ('style_profiles/style_pack_manifest_v1.json',manifest),('palettes/palette_catalog_v1.json',palettes)]:
        save(ASSETS/path,data)
    save(FIXTURES/'scenarios.json',scenarios)


if __name__=='__main__':
    main()
