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

    def test_logged_compositional_failure_is_repaired_to_native_church(self):
        from app.services.building_contract import apply_building_contract
        plan = {'mode': 'build', 'proportion_hints': {'typology': 'gothic_cathedral_hall'}, 'components': [
            {'component_type': 'MASS_MAIN', 'params': {'component_id': 'nave_main'}, 'dimensions': {'width': 13, 'depth': 35, 'height': 16}, 'relative_position': {'x': 0, 'y': 0, 'z': 0}},
            {'component_type': 'FACADE_WINDOWS', 'params': {'component_id': 'aisle_west_windows', 'host_id': 'aisle_west'}}]}
        text = '生成一座哥特式教堂，宽25格、深35格，中殿墙高16格，入口朝南。不要双塔，不要玫瑰窗，不要飞扶壁。'
        result = apply_building_contract(plan, text, finalize=True)
        self.assertNotIn('capability_gap', result)
        self.assertEqual(len(result['components']), 1)
        component = result['components'][0]
        self.assertEqual(component['component_type'], 'STRUCTURE')
        self.assertEqual(component['params']['width'], 25)
        self.assertEqual(component['params']['depth'], 35)
        self.assertEqual(component['params']['facing'], 'SOUTH')
        self.assertFalse(component['params']['includeTowers'])
        self.assertEqual(result, apply_building_contract(result, text, finalize=True))

    def test_route_repair_does_not_replace_named_landmarks_or_unsupported_variants(self):
        from app.services.gothic_building_contract import repair_gothic_route
        for text in ['哥特式教堂，宽24格', '哥特式教堂，平屋顶', '巴黎圣母院哥特式教堂', '两座哥特式教堂']:
            plan = {'proportion_hints': {'typology': 'gothic_cathedral_hall'}, 'components': [{'component_type': 'MASS_MAIN', 'params': {'component_id': 'original'}}]}
            repair_gothic_route(plan, text)
            self.assertEqual(plan['components'][0]['component_type'], 'MASS_MAIN')
