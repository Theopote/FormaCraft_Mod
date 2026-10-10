import unittest
from app.services.building_contract import apply_building_contract


def plan():
    return {'mode': 'build', 'components': [
        {'component_type': 'MASS_MAIN', 'dimensions': {'width': 25, 'depth': 25, 'height': 11},
         'relative_position': {'x': 0, 'y': 0, 'z': 0}, 'params': {'component_id': 'house', 'anchor_mode': 'min_corner', 'floor_height': 5, 'floor_count': 2}},
        *[{'component_type': 'FACADE_WINDOWS', 'dimensions': {'width': 25, 'depth': 25, 'height': 10},
           'relative_position': {'x': 0, 'y': 0, 'z': 0}, 'params': {'component_id': wall, 'host_id': 'house', 'wall': wall}}
          for wall in ('front', 'left_right')]]}


class WindowLanguageContractTest(unittest.TestCase):
    def windows(self, result):
        return {c['params']['wall']: c['params'] for c in result['components'] if c['component_type'] == 'FACADE_WINDOWS'}

    def test_counts_bind_only_matching_facades_and_are_idempotent(self):
        text = '入口两侧各两扇窗。每面侧墙每层三扇窗。'
        result = apply_building_contract(plan(), text)
        windows = self.windows(result)
        self.assertEqual(windows['front']['count_per_side'], 2)
        self.assertNotIn('window_per_wall', windows['front'])
        self.assertEqual(windows['left_right']['window_per_wall'], 3)
        self.assertNotIn('count_per_side', windows['left_right'])
        self.assertEqual(result['components'], apply_building_contract(result, text)['components'])

    def test_second_floor_mask_is_bound_to_owned_facades(self):
        result = apply_building_contract(plan(), '只在二楼开窗。')
        self.assertTrue(all(p['window_floors'] == [2] for p in self.windows(result).values()))
        self.assertTrue(all(r['status'] == 'planned' for r in result['proportion_hints']['window_language_report']['requirements']))

    def test_unknown_wall_scope_is_not_guessed(self):
        source = plan()
        for c in source['components'][1:]: c['params'].pop('wall')
        result = apply_building_contract(source, '入口两侧各2扇窗。')
        report = result['proportion_hints']['window_language_report']['requirements']
        self.assertEqual(report[0]['status'], 'unresolved_facade_scope')
        self.assertTrue(all('count_per_side' not in c['params'] for c in result['components']))

    def test_conflicting_counts_do_not_override_model_parameters(self):
        result = apply_building_contract(plan(), '入口两侧各2扇窗。入口两侧各3扇窗。')
        self.assertTrue(all(r['status'] == 'conflicting_values' for r in result['proportion_hints']['window_language_report']['requirements']))
        self.assertNotIn('count_per_side', self.windows(result)['front'])

    def test_named_building_scope_is_reported_instead_of_broadened(self):
        result = apply_building_contract(plan(), '左栋入口两侧各2扇窗。')
        self.assertEqual(result['proportion_hints']['window_language_report']['requirements'][0]['status'], 'unresolved_building_scope')

    def test_negated_templates_are_not_positive_requirements(self):
        result = apply_building_contract(plan(), '不要入口两侧各2扇窗。不要只在二楼开窗。')
        self.assertEqual(result['proportion_hints']['window_language_report']['requirements'], [])

    def test_scoped_floor_mask_does_not_change_other_walls(self):
        result = apply_building_contract(plan(), '正面只在二楼开窗。')
        windows = self.windows(result)
        self.assertEqual(windows['front']['window_floors'], [2])
        self.assertNotIn('window_floors', windows['left_right'])

    def test_floor_specific_count_is_reported_without_broadening(self):
        result = apply_building_contract(plan(), '二楼入口两侧各2扇窗。')
        report = result['proportion_hints']['window_language_report']['requirements']
        self.assertEqual(report[0]['status'], 'planned')
        self.assertEqual(self.windows(result)['front']['window_counts_by_floor'], {'2': {'count_per_side': 2}})
        self.assertNotIn('count_per_side', self.windows(result)['front'])

    def test_building_storey_count_is_not_a_floor_specific_window_scope(self):
        result = apply_building_contract(plan(), '生成两层住宅入口两侧各2扇窗。')
        self.assertEqual(self.windows(result)['front']['count_per_side'], 2)

    def test_different_floor_counts_and_shorthand_are_idempotent(self):
        text = '一楼入口两侧各一扇窗，二楼各两扇窗。'
        result = apply_building_contract(plan(), text)
        self.assertEqual(self.windows(result)['front']['window_counts_by_floor'], {'1': {'count_per_side': 1}, '2': {'count_per_side': 2}})
        self.assertNotIn('window_counts_by_floor', self.windows(result)['left_right'])
        self.assertEqual(result['components'], apply_building_contract(result, text)['components'])

    def test_general_default_and_floor_override_coexist(self):
        result = apply_building_contract(plan(), '每面侧墙每层三扇窗。二楼每面侧墙各一扇窗。')
        params = self.windows(result)['left_right']
        self.assertEqual(params['window_per_wall'], 3)
        self.assertEqual(params['window_counts_by_floor'], {'2': {'window_per_wall': 1}})

    def test_conflict_is_limited_to_same_floor(self):
        result = apply_building_contract(plan(), '一楼入口两侧各一扇窗。二楼入口两侧各两扇窗。二楼入口两侧各三扇窗。')
        self.assertEqual(self.windows(result)['front']['window_counts_by_floor'], {'1': {'count_per_side': 1}})
        self.assertEqual([r['status'] for r in result['proportion_hints']['window_language_report']['requirements']], ['planned', 'conflicting_values', 'conflicting_values'])
