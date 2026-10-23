"""Dedicated Tulou assets using the existing native blueprint/generator bridge."""
from .expand_p2_assets import ASSETS, FIXTURES, load, save


def main():
    pid='PALETTE_TULOU_EARTH_A'
    path=ASSETS/'palettes/palette_catalog_v1.json'; palettes=load(path)
    parts={'WALL_BASE':'packed_mud','WALL_FOUNDATION':'cobblestone','FLOORING':'spruce_planks',
           'FLOOR':'spruce_planks','ROOF_TILE':'deepslate_tiles','ROOF':'deepslate_tiles','WINDOW':'glass_pane'}
    palettes['palettes'][pid]=dict(meta=dict(display_name='Tulou earth and timber',tags=['tulou','earthen','courtyard']),
                                  parts={k:[dict(id='minecraft:'+v,weight=100)] for k,v in parts.items()})
    save(path,palettes)
    path=ASSETS/'style_profiles/style_profile_catalog_v1.json'; profiles=load(path)
    profiles['profiles']['Chinese_Vernacular_Tulou']['defaults']['components']['palette_id']=pid
    profiles['profiles']['Chinese_Vernacular_Tulou']['defaults']['materials']['floor']=['minecraft:spruce_planks']
    save(path,profiles)
    path=ASSETS/'style_profiles/style_identity_catalog_v1.json'; identities=load(path)
    identities['lexical_aliases']['Chinese_Vernacular_Tulou']=list(dict.fromkeys([
        *identities['lexical_aliases']['Chinese_Vernacular_Tulou'],'tulou','客家圆形土楼','hakka circular tulou']))
    save(path,identities)
    # A regional dwelling type is not one uniquely identified real building.
    path=ASSETS/'archetypes/archetypes_v1.json'; archetypes=load(path)
    tulou=next(a for a in archetypes['archetypes'] if a['id']=='tulou')
    if 'genericAliases' not in tulou:
        import json
        raw=path.read_text(encoding='utf-8')
        needle='      "id": "tulou",'
        assert needle in raw
        raw=raw.replace(needle,needle+'\n      "properNounAliases": [],\n      "genericAliases": '+
                        json.dumps(tulou['aliases'],ensure_ascii=False)+',',1)
        path.write_text(raw,encoding='utf-8')
    names=[]; scenarios=load(FIXTURES/'scenarios.json')
    for radius,floors in ((10,2),(14,3)):
        name=f'tulou_ring_{radius}.json';names.append(name)
        blueprint=dict(blueprint_type='tulou',overall_dimensions=dict(radius=radius,floors=floors,door_facing='SOUTH'),
                       parameters=dict(ringThickness=4,detailLevel='aesthetic'))
        plan=dict(mode='build',style_profile='Chinese_Vernacular_Tulou',anchor=dict(x=0,y=64,z=0),
                  global_constraints=dict(facing='SOUTH',symmetry='NONE',terrain_strategy='PRESERVE'),
                  layout=dict(skeleton_type='COMPOUND',path_based=False,slots=[]),
                  proportion_hints=dict(typology='tulou_ring',proportion_card_id='tulou_ring',
                      height_to_width=(floors*4+2)/(2*radius+1),courtyard_ratio=((radius-4)/radius)**2),
                  components=[dict(component_type='STRUCTURE',relative_position=dict(x=0,y=0,z=0),
                    dimensions=dict(width=radius*2+1,depth=radius*2+1,height=floors*4+8),
                    features=['structure_generator:tulou'],params=dict(component_id='tulou_native',
                        anchor_mode='min_corner',blueprint=blueprint,paletteId=pid))])
        save(FIXTURES/'plans'/name,plan)
        save(ASSETS/'llmplan_examples'/name,dict(description='Native Tulou blueprint: earth ring, open courtyard and timber floors.',
             promptHints=['客家圆形土楼','hakka circular tulou'],plan=plan))
        scenarios=[s for s in scenarios if s['id']!=name[:-5]]
        scenarios.append(dict(id=name[:-5],prompt='build a hakka circular tulou',plan_fixture='plans/'+name,ci=True,
                              tags=['A','tulou','native_blueprint']))
    save(FIXTURES/'scenarios.json',scenarios)
    save(ASSETS/'culture_cards/tulou_ring.json',dict(id='tulou_ring',styleId='Chinese_Vernacular_Tulou',
         intents=['TULOU','COMMUNAL_COURTYARD'],keywords=['土楼','客家圆形土楼','tulou','hakka circular tulou'],
         synonyms={},keywordWeights={'土楼':12,'客家圆形土楼':20,'tulou':12,'hakka circular tulou':20},
         negativeKeywords=['不要土楼','without tulou'],proportionCardId='tulou_ring',
         llmPlanExampleRefs=names,exampleRefs=[],archetypes=[dict(name='native_tulou_ring',constraints=[
             'Use STRUCTURE with the existing tulou blueprint and explicit radius/floors; no new typology interpreter is claimed.',
             'Keep the central courtyard open, the earthen exterior wall and annular timber floor bands.',
             'Examples use the native TulouGenerator; live routing, access and historical fidelity require separate evidence.'
         ])]))
    save(ASSETS/'proportion_cards/tulou_ring.json',dict(id='tulou_ring',typology='tulou_ring',
         matchKeywords=['土楼','tulou'],ratios=dict(height_to_width=dict(min=.25,ideal=.5,max=1),
         courtyard_ratio=dict(min=.25,ideal=.45,max=.65)),aiInstruction='Preserve explicit radius and floors; courtyard stays open to the sky.'))


if __name__=='__main__':
    main()
