"""Author four-wing Huizhou skywell compounds at two fixed envelope sizes."""
from copy import deepcopy
from .expand_p2_assets import ASSETS,FIXTURES,load,save,primitive


def main():
    template=load(FIXTURES/'plans/huizhou_vernacular_13.json')
    scenarios=load(FIXTURES/'scenarios.json'); names=[]
    for size in (17,21):
        side=(size-7)//2; front=side+1; depth=front*2+7
        plan=deepcopy(template); plan['components']=[]
        plan['global_constraints'].update(symmetry='NONE',terrain_strategy='PRESERVE')
        plan['proportion_hints'].update(typology='huizhou_tianjing',proportion_card_id='huizhou_tianjing',
                                        height_to_width=6/size,courtyard_ratio=49/(size*depth))
        wings=[('front',0,0,size,front),('rear',0,front+7,size,front),
               ('left',0,front,side,7),('right',size-side,front,side,7)]
        for label,x,z,w,d in wings:
            body,roof=deepcopy(template['components'])
            for c in (body,roof):
                c['relative_position'].update(x=x,z=z)
                c['dimensions'].update(width=w,depth=d)
            body['params']['component_id']=label
            body['params'].update(window_ratio=.18 if label in ('front','rear') else 0,
                                  suppress_windows=True,wall_thickness=1)
            roof['params'].update(component_id=label+'_roof',host_id=label,roof_height=3)
            roof['dimensions']['height']=3
            plan['components'] += [body,roof]
        plan['components'].append(primitive('skywell_floor',side,0,front,7,7,1,'box','stone_bricks'))
        def entrance(label,host,x,z,w,d,facing):
            plan['components'].append(dict(component_type='ENTRANCE',relative_position=dict(x=x,y=1,z=z),
                dimensions=dict(width=w,depth=d,height=4),features=['entrance'],params=dict(component_id=label,
                host_id=host,anchor_mode='min_corner',facing=facing,door_width=3,door_height=3,canopy_depth=0)))
        entrance('front_passage','front',size//2-2,0,5,1,'SOUTH')
        entrance('front_skywell_exit','front',size//2-2,front-1,5,1,'NORTH')
        entrance('rear_portal','rear',size//2-2,front+7,5,1,'SOUTH')
        entrance('left_portal','left',side-1,front+1,1,5,'WEST')
        entrance('right_portal','right',size-side,front+1,1,5,'EAST')
        # Explicit inward-facing lattice windows; automatic exterior windows stay suppressed.
        for host,x,z in [('front',size//2-3,front-1),('front',size//2+3,front-1),
                         ('rear',size//2-3,front+7),('rear',size//2+3,front+7)]:
            plan['components'].append(dict(component_type='FACADE_WINDOWS',
                relative_position=dict(x=x,y=2,z=z),dimensions=dict(width=1,depth=1,height=3),features=['lattice'],
                params=dict(component_id=f'{host}_window_{x}_{z}',host_id=host,anchor_mode='min_corner',
                            window_style='lattice',window_block='minecraft:spruce_fence',
                            window_width=1,window_height=2,window_count=1,rows=1,
                            floor_count=1,floor_height=3,suppress_windows=False,window_ratio=.18,reserve_entrance_bay=False)))
        name=f'huizhou_tianjing_{size}.json';names.append(name)
        save(FIXTURES/'plans'/name,plan)
        save(ASSETS/'llmplan_examples'/name,dict(prompt='build a huizhou tianjing compound',plan=plan))
        scenarios=[s for s in scenarios if s['id']!=name[:-5]]
        scenarios.append(dict(id=name[:-5],prompt='build a huizhou tianjing compound',plan_fixture='plans/'+name,ci=True,tags=['A','huizhou','skywell']))
    save(FIXTURES/'scenarios.json',scenarios)
    save(ASSETS/'culture_cards/huizhou_tianjing.json',dict(id='huizhou_tianjing',styleId='Chinese_Vernacular_Huizhou',
        intents=['HUIZHOU_TIANJING'],keywords=['徽派天井院落','徽州天井','huizhou tianjing compound'],synonyms={},
        keywordWeights={'徽派天井院落':35,'徽州天井':35,'huizhou tianjing compound':35},negativeKeywords=['不要徽派','not huizhou'],
        proportionCardId='huizhou_tianjing',llmPlanExampleRefs=names,exampleRefs=[],archetypes=[dict(name='four_wing_skywell',constraints=[
            'Four distinct white-wall/dark-tile wings enclose an open 7x7 skywell. Preserve explicit horse_head_walls=false.',
            'Front passage joins the exterior to the skywell; each other wing has a courtyard portal.',
            'Four explicit inward lattice windows flank front/rear hall portals; leave narrow side-wing corner posts intact.',
            'Fixed flat dry reference site; no automatic terrain fitting, drainage or historical reconstruction is claimed.'])]))
    save(ASSETS/'proportion_cards/huizhou_tianjing.json',dict(id='huizhou_tianjing',typology='huizhou_tianjing',
        matchKeywords=['徽派天井院落','huizhou tianjing compound'],ratios=dict(courtyard_ratio=dict(min=.08,ideal=.13,max=.2)),
        aiInstruction='Keep the central skywell open and all four wings reachable from it.'))


if __name__=='__main__':
    main()
