import unittest
from app.services.gothic_building_contract import apply_gothic_building_contract

class GothicBuildingContractTest(unittest.TestCase):
    def test_vault_shell_optin_optout_and_scope(self):
        for text, expected in [('设置薄拱顶壳体', True), ('取消拱顶壳体', False), ('include vault shell', True), ('no vault shell', False)]:
            plan = self.plan()
            apply_gothic_building_contract(plan, text)
            self.assertEqual(plan['components'][0]['params']['includeVaultShell'], expected)
        plan = self.plan()
        apply_gothic_building_contract(plan, '左栋设置薄拱顶壳体')
        self.assertNotIn('includeVaultShell', plan['components'][0]['params'])
    def test_rib_skeleton_optin_optout_and_scope(self):
        for text, expected in [('设置室内肋拱', True), ('取消室内肋拱', False), ('include rib vault', True), ('no rib vault', False), ('设置室内肋拱，但是取消室内肋拱', False)]:
            plan = self.plan()
            apply_gothic_building_contract(plan, text)
            self.assertEqual(plan['components'][0]['params']['includeRibVault'], expected)
        plan = self.plan()
        apply_gothic_building_contract(plan, '左栋设置室内肋拱')
        self.assertNotIn('includeRibVault', plan['components'][0]['params'])

    def test_entrance_steps_can_be_disabled(self):
        plan = self.plan()
        apply_gothic_building_contract(plan, '不要入口大台阶')
        self.assertFalse(plan['components'][0]['params']['includeEntranceSteps'])

    def test_faceted_apse_and_choir_require_explicit_unique_scope(self):
        plan = self.plan()
        apply_gothic_building_contract(plan, '设置折角后殿，设置唱诗席')
        self.assertTrue(plan['components'][0]['params']['includeApse'])
        self.assertTrue(plan['components'][0]['params']['includeChoir'])
        apply_gothic_building_contract(plan, '不要后殿，取消唱诗席')
        self.assertFalse(plan['components'][0]['params']['includeApse'])
        self.assertFalse(plan['components'][0]['params']['includeChoir'])
        plan = self.plan()
        apply_gothic_building_contract(plan, '左栋设置折角后殿')
        self.assertNotIn('includeApse', plan['components'][0]['params'])

    def test_native_route_only_accepts_the_supported_faceted_terminal(self):
        from app.services.building_contract import apply_building_contract
        from app.services.gothic_building_contract import repair_gothic_route
        plan = {'mode': 'build', 'proportion_hints': {'typology': 'gothic_cathedral_hall'}, 'components': []}
        result = apply_building_contract(plan, '哥特式教堂，总宽35格、深45格，设置折角后殿，设置唱诗席', finalize=True)
        self.assertTrue(result['components'][0]['params']['includeApse'])
        self.assertTrue(result['components'][0]['params']['includeChoir'])
        self.assertEqual(result['components'][0]['params']['anchorMode'], 'center')
        plan = {'proportion_hints': {'typology': 'gothic_cathedral_hall'}, 'components': []}
        repair_gothic_route(plan, '哥特式教堂，设置半圆后殿')
        self.assertEqual(plan['components'], [])

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

    def test_cruciform_route_keeps_total_dimensions_and_rejects_too_narrow(self):
        from app.services.gothic_building_contract import repair_gothic_route
        for width in (25, 35):
            plan = {'proportion_hints': {'typology': 'gothic_cathedral_hall'}, 'components': []}
            repair_gothic_route(plan, f'哥特式教堂，十字形平面，宽{width}格，深45格')
            self.assertEqual(plan['components'][0]['params']['planVariant'], 'cruciform')
            self.assertEqual(plan['components'][0]['dimensions']['width'], width)
        plan = {'proportion_hints': {'typology': 'gothic_cathedral_hall'}, 'components': []}
        repair_gothic_route(plan, '哥特式教堂，十字形，宽17格')
        self.assertEqual(plan['components'], [])

    def test_explicit_roof_rise_updates_native_height(self):
        from app.services.gothic_building_contract import repair_gothic_route
        plan = {'proportion_hints': {'typology': 'gothic_cathedral_hall'}, 'components': []}
        repair_gothic_route(plan, '哥特式教堂，宽35格，深45格，屋顶升高24格')
        component = plan['components'][0]
        self.assertEqual(component['params']['roofRise'], 24)
        self.assertGreaterEqual(component['dimensions']['height'], 45)

    def test_window_tracery_optout_preserves_glazing_and_global_window_scope(self):
        plan = self.plan()
        apply_gothic_building_contract(plan, '不要窗花')
        self.assertFalse(plan['components'][0]['params']['includeWindowTracery'])
        self.assertNotIn('includeWindows', plan['components'][0]['params'])
        apply_gothic_building_contract(plan, '整座建筑不设窗户')
        self.assertFalse(plan['components'][0]['params']['includeWindows'])
        self.assertFalse(plan['components'][0]['params']['includeRoseWindow'])
        plan = self.plan()
        apply_gothic_building_contract(plan, '前山墙不要窗户')
        self.assertNotIn('includeWindows', plan['components'][0]['params'])

    def test_single_tower_can_replace_twins_and_no_towers_is_distinct(self):
        plan = self.plan()
        apply_gothic_building_contract(plan, '不要双塔，设置单塔')
        self.assertEqual(plan['components'][0]['params']['towerArrangement'], 'single')
        self.assertTrue(plan['components'][0]['params']['includeTowers'])
        plan = self.plan()
        apply_gothic_building_contract(plan, '无塔')
        self.assertEqual(plan['components'][0]['params']['towerArrangement'], 'none')

    def test_native_tower_height_language_survives_route_repair(self):
        from app.services.building_contract import apply_building_contract
        plan = {'mode': 'build', 'proportion_hints': {'typology': 'gothic_cathedral_hall'}, 'components': []}
        result = apply_building_contract(plan, '哥特式教堂，宽35格，深45格，不要双塔，设置单塔，塔身高24格，尖顶高14格', finalize=True)
        params = result['components'][0]['params']
        self.assertEqual((params['towerHeight'], params['spireHeight'], params['towerArrangement']), (24,14,'single'))
        self.assertNotIn('capability_gap', result)
