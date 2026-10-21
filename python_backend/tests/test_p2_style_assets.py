import json
from copy import deepcopy
from pathlib import Path
import unittest
from app.services.ornament_contract import apply_ornament_contracts

ROOT=Path(__file__).resolve().parents[2]


class P2StyleAssetsTest(unittest.TestCase):
    def plan(self):
        return json.loads((ROOT/'python_backend/eval/fixtures/plans/p2_cyberpunk_block_13.json').read_text(encoding='utf-8'))

    def test_emission_negation_and_positive_contract(self):
        for text in ('不要霓虹','关闭发光','without neon','disable emission'):
            plan=self.plan()
            apply_ornament_contracts(plan,text)
            self.assertFalse(plan['components'][0]['params']['emissive_enabled'])
        plan=self.plan(); plan['components'][0]['params']['emissive_enabled']=False
        apply_ornament_contracts(plan,'enable neon')
        self.assertTrue(plan['components'][0]['params']['emissive_enabled'])

    def test_local_style_and_multi_building_scope_are_preserved(self):
        plan=self.plan(); plan['components'][0]['params']['style_profile']='Modern_International'
        apply_ornament_contracts(plan,'不要霓虹')
        self.assertTrue(plan['components'][0]['params']['emissive_enabled'])
        plan=self.plan(); plan['components'].append(deepcopy(plan['components'][0]))
        apply_ornament_contracts(plan,'不要霓虹')
        self.assertTrue(plan['components'][0]['params']['emissive_enabled'])
        self.assertEqual('unresolved_building_scope',plan['proportion_hints']['emissive_contract']['requirements'][0]['status'])
