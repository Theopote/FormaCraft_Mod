import json
import unittest
from pathlib import Path
from app.services.building_contract import apply_building_contract


class ChineseHallAcceptanceTest(unittest.TestCase):
    def test_shared_requests_reach_native_parameters(self):
        cases=json.loads((Path(__file__).resolve().parents[2]/'src/test/resources/chinese_hall_acceptance_cases.json').read_text(encoding='utf-8'))
        for case in cases:
            with self.subTest(case=case['id']):
                # Routing and dimensions are prerequisites; this test verifies explicit controls after routing.
                plan={'components':[{'component_type':'STRUCTURE','params':{
                    'typology_id':'tailiang_timber_hall','component_id':'hall',
                    'width':25,'depth':15}}]}
                out=apply_building_contract(plan,case['prompt'],finalize=True)
                self.assertNotIn('capability_gap',out)
                params=out['components'][0]['params']
                for key,value in case['params'].items(): self.assertEqual(value,params[key],key)
                for contract in ('chinese_roof_contract','chinese_material_contract'):
                    self.assertTrue(all(r['status']=='planned' for r in out['proportion_hints'][contract]['requirements']))
