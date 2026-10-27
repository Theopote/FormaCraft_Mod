"""Bounded native stair repair for towers whose model envelope cannot fit its flights."""
from copy import deepcopy
import re

def fit_narrow_tower_stairs(plan: dict) -> None:
    parts=plan.get('components',[])
    hints=plan.get('proportion_hints') or {}
    text=str((hints.get('architecture_intent') or {}).get('original_text',''))
    # Exact requested footprints and selected boundaries remain authoritative.
    if re.search(r'\d+\s*[×xX*]\s*\d+',text) or (plan.get('genome') or {}).get('constraints',{}).get('insideSelectionOnly'): return
    if (plan.get('plan_meta') or {}).get('allow_compiler_satellites') is False: return
    additions=[]
    for host in list(parts):
        hp=host.get('params') or {}; dims=host.get('dimensions') or {}
        if host.get('component_type') not in ('MASS_MAIN','TOWER','MASS_SECONDARY') or hp.get('extrude_mode')=='plate': continue
        w,d,h=(dims.get(k,0) for k in ('width','depth','height'))
        if not (6<=w<=7 and 6<=d<=7 and h>=12 and hp.get('shape','rectangle')=='rectangle'): continue
        flights=[c for c in parts if c.get('component_type')=='STRUCTURE' and c.get('slot_id')==host.get('slot_id')
                 and (c.get('params') or {}).get('host_id')==hp.get('component_id')
                 and not c['params'].get('exterior') and all(isinstance(c['params'].get(k),dict) for k in ('from','to'))]
        if len(flights)<2 or any(c['params'].get('circulation_source')=='bounded_tower_switchback' for c in flights): continue
        flights.sort(key=lambda c:c['relative_position']['y']+c['params']['from']['y'])
        width=max(int(c['params'].get('width',2)) for c in flights)
        levels=[(c['relative_position']['y']+c['params']['from']['y'],c['relative_position']['y']+c['params']['to']['y']) for c in flights]
        if width!=2 or any(b<=a or b-a>5 for a,b in levels) or any(a!=levels[i-1][1] for i,(a,b) in enumerate(levels) if i): continue
        if all(b-a<=w-3 for a,b in levels): continue
        from .resolved_geometry import mass_origin
        old_origin=mass_origin(host);origin=dict(old_origin)
        # Grow away from the compound center: keep the inward connection plane unchanged.
        slots={s.get('slot_id'):s.get('anchor') or {} for s in (plan.get('layout') or {}).get('slots',[])}
        anchor=slots.get(host.get('slot_id'),{})
        if anchor.get('x',0)<0: origin['x']-=8-w
        origin['z']-=(8-d)//2
        hp['anchor_mode']='min_corner';host['relative_position']=origin;dims.update(width=8,depth=8)
        for roof in parts:
            if roof.get('component_type')=='ROOF' and (roof.get('params') or {}).get('host_id')==hp.get('component_id'):
                e=int(roof['params'].get('overhang',1));roof['dimensions'].update(width=8+2*e,depth=8+2*e)
        for i,(source,(low,high)) in enumerate(zip(flights,levels)):
            lane=2 if i%2==0 else 6;start,end=(1,6) if i%2==0 else (6,1)
            p=source['params'];source['relative_position']=dict(origin);source['dimensions']=dict(dims)
            p.update(anchor_mode='min_corner',width=width,landing_length=1,support=False,
                     circulation_source='bounded_tower_switchback',compiler_connected_stair_chain=True,
                     **{'from':{'x':start,'y':low-origin['y'],'z':lane},'to':{'x':end,'y':high-origin['y'],'z':lane}})
            if i+1<len(flights):
                additions.append({'component_type':'MASS_SECONDARY','slot_id':host.get('slot_id'),
                    'relative_position':{'x':origin['x']+end,'y':high,'z':origin['z']+3},
                    'dimensions':{'width':1,'depth':2,'height':1},'features':['stair_turn_landing'],
                    'params':{'component_id':str(p.get('component_id'))+'#turn','host_id':hp['component_id'],
                              'anchor_mode':'min_corner','extrude_mode':'plate','plate_role':'floor',
                              'floor_block':p.get('floor','minecraft:stone_bricks')}})
    parts.extend(additions)
