import json
import unittest
from pathlib import Path
from app.services.building_contract import apply_building_contract
from app.services.circulation_plan_normalizer import normalize_circulation_plan


class RetryReplayTest(unittest.TestCase):
    def test_module_count_completes_empty_single_and_partial_layouts(self):
        for slots in ([],[{'slot_id':'first','anchor':{'x':0,'y':0,'z':0}}],
                      [{'slot_id':'first','anchor':{'x':0,'y':0,'z':0}}, {'slot_id':'second','anchor':{'x':33,'y':0,'z':0}}]):
            plan={'mode':'build','layout':{'skeleton_type':'COMPOUND','slots':slots},
                  'components':[{'component_type':'MODULE','slot_id':'first' if slots else None,
                                 'relative_position':{'x':0,'y':0,'z':0},'dimensions':{'width':25,'depth':25,'height':13},
                                 'features':['landmark:tulou'],'params':{'component_id':'tulou','module_id':'tulou'}}]}
            result=apply_building_contract(plan,'建造三栋福建土楼，间隔至少8格。',finalize=True)
            self.assertEqual(3,len(result['components']))
            self.assertEqual(3,len({c['slot_id'] for c in result['components']}))

    def test_actual_plans_and_geometry_contracts(self):
        root = Path(__file__).parent / 'fixtures/complex_build_retest'
        for i in range(1,9):
            with self.subTest(plan=i):
                raw = json.loads((root/f'oct20_retry_{i}.json').read_text(encoding='utf-8'))
                text = raw['proportion_hints']['architecture_intent']['original_text']
                result = normalize_circulation_plan(apply_building_contract(raw,text,finalize=True))
                self.assertNotIn('capability_gap',result)
                self.assertEqual(result,normalize_circulation_plan(apply_building_contract(result,text,finalize=True)))
                self.assertEqual(result,json.loads((root/f'oct20_retry_{i}.normalized.json').read_text(encoding='utf-8')))
                if i==1:
                    self.assertEqual(3,len(result['components']))
                    slots = result['layout']['slots']
                    self.assertEqual(3,len({c['slot_id'] for c in result['components']}))
                    self.assertTrue(all(b['anchor']['x']-a['anchor']['x']>=33 for a,b in zip(slots,slots[1:])))
                if i==2:
                    stair = next(c for c in result['components'] if c['params'].get('exterior'))
                    self.assertEqual(17,stair['relative_position']['x'])
                    self.assertEqual(1,stair['params']['landing_length'])
                if i==3:
                    door = next(c for c in result['components'] if c['params'].get('component_id')=='inn_terrace_door')
                    self.assertEqual('inn_terrace',door['params']['platform_connection'])
                    self.assertEqual('SOUTH',door['params']['facing'])
                if i==6:
                    body = next(c for c in result['components'] if c['component_type']=='MASS_MAIN')
                    self.assertEqual('rectangle',body['params']['shape'])
                    self.assertEqual(5,len(body['params']['masses']))
                    self.assertEqual([35,31,27,23,19],[m['dimensions']['width'] for m in body['params']['masses']])
