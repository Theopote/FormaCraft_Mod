import unittest
from app.services.gothic_building_contract import apply_gothic_building_contract

class GothicBuildingContractTest(unittest.TestCase):
    def plan(self):
        return {'components': [{'component_type': 'STRUCTURE', 'features': ['typology:gothic_cathedral_hall'], 'params': {'component_id': 'church'}}]}

    def test_explicit_optouts_bind_unique_gothic_host_and_are_idempotent(self):
        plan = self.plan()
        text = '哥特式教堂，不要双塔，不要玫瑰花窗，不要飞扶壁'
        apply_gothic_building_contract(plan, text)
        self.assertEqual(plan['components'][0]['params'], {'component_id': 'church', 'includeTowers': False, 'includeRoseWindow': False, 'includeButtresses': False})
        original = repr(plan)
        apply_gothic_building_contract(plan, text)
        self.assertEqual(repr(plan), original)

    def test_multibuilding_scope_and_unrelated_types_are_not_broadened(self):
        for kind in ['named', 'multiple', 'unrelated']:
            plan = self.plan()
            text = '不要双塔'
            if kind == 'named': text = '左栋不要双塔'
            if kind == 'multiple': plan['components'].append(dict(plan['components'][0]))
            if kind == 'unrelated': plan['components'][0]['features'] = ['typology:tailiang_timber_hall']
            apply_gothic_building_contract(plan, text)
            self.assertTrue(all('includeTowers' not in c['params'] for c in plan['components']))
            self.assertEqual(plan['proportion_hints']['gothic_building_contract']['requirements'][0]['status'], 'unresolved_building_scope')

    def test_english_optouts_and_positive_mentions(self):
        plan = self.plan()
        apply_gothic_building_contract(plan, 'no twin towers, no rose window, no flying buttresses')
        self.assertFalse(plan['components'][0]['params']['includeRoseWindow'])
        apply_gothic_building_contract(plan, '玫瑰窗与双塔')
        self.assertEqual(plan['proportion_hints']['gothic_building_contract']['requirements'], [])
