import json
import unittest
from copy import deepcopy
from pathlib import Path

from app.services.building_contract import apply_building_contract
from app.services.circulation_plan_normalizer import normalize_circulation_plan
from app.services.bridge_plan_normalizer import normalize_hosted_connections

FIXTURES = Path(__file__).parent / 'fixtures' / 'complex_build_retest'


class BridgePlanNormalizerTest(unittest.TestCase):
    def load(self, i):
        return json.loads((FIXTURES / f'oct17_evening_{i}.json').read_text(encoding='utf-8'))

    def normalize(self, plan):
        text = plan['proportion_hints']['architecture_intent']['original_text']
        return normalize_circulation_plan(apply_building_contract(plan, text, finalize=True))

    def test_detached_wings_keep_their_storeys_and_own_floors_and_roofs(self):
        result = self.normalize(self.load(2))
        parts = {c['params']['component_id']: c for c in result['components']}
        self.assertEqual([8, 12, 8], [parts[n]['dimensions']['height']
                                     for n in ('hotel_lobby', 'hotel_mid_volume', 'hotel_high_volume')])
        self.assertEqual('hotel_mid_volume', parts['mid_floor_2']['params']['host_id'])
        self.assertEqual('hotel_mid_volume', parts['roof_mid']['params']['host_id'])
        self.assertEqual('hotel_mid_volume', parts['bridge_mid_high']['params']['host_id'])
        self.assertNotIn('connection_level', parts['bridge_mid_high']['params'])
        self.assertEqual(8, parts['bridge_mid_high']['relative_position']['y'])
        self.assertEqual(result, normalize_circulation_plan(result))
        expected = json.loads((FIXTURES / 'oct17_evening_2.normalized.json').read_text(encoding='utf-8'))
        self.assertEqual(expected, result)

    def test_short_bridge_and_its_roof_extend_without_moving_buildings(self):
        original = self.load(5)
        result = self.normalize(original)
        before = {c['params']['component_id']: c for c in original['components']}
        after = {c['params']['component_id']: c for c in result['components']}
        for name in ('office_main', 'workshop_main'):
            self.assertEqual(before[name]['relative_position'], after[name]['relative_position'])
        self.assertEqual(9, after['corridor_bridge']['dimensions']['width'])
        self.assertEqual(9, after['corridor_roof']['dimensions']['width'])
        self.assertEqual('office_main', after['corridor_roof']['params']['host_id'])
        landing = after['corridor_bridge#target_landing']
        self.assertEqual('workshop_main', landing['params']['host_id'])
        self.assertEqual(4, landing['relative_position']['y'])
        self.assertEqual({'width': 3, 'depth': 3, 'height': 1}, landing['dimensions'])
        self.assertEqual(result, normalize_circulation_plan(result))
        self.assertEqual(result, json.loads((FIXTURES / 'oct17_evening_5.normalized.json').read_text(encoding='utf-8')))

    def test_distant_connection_is_not_stretched(self):
        plan = self.load(5)
        next(s for s in plan['layout']['slots'] if s['slot_id'] == 'workshop')['anchor']['x'] += 30
        normalize_hosted_connections(plan)
        bridge = next(c for c in plan['components'] if c['params'].get('component_id') == 'corridor_bridge')
        self.assertEqual(6, bridge['dimensions']['width'])

    def test_contract_does_not_shorten_detached_wings_with_tier_indices(self):
        plan = self.load(2)
        sizes = {'hotel_lobby': (8, 2), 'hotel_mid_volume': (12, 3), 'hotel_high_volume': (8, 2)}
        for c in plan['components']:
            identity = c['params'].get('component_id')
            if identity in sizes:
                c['dimensions']['height'], c['params']['floor_count'] = sizes[identity]
        text = plan['proportion_hints']['architecture_intent']['original_text']
        result = apply_building_contract(plan, text, finalize=True)
        for c in result['components']:
            identity = c['params'].get('component_id')
            if identity in sizes:
                self.assertEqual(sizes[identity], (c['dimensions']['height'], c['params']['floor_count']))

    def test_strict_mode_keeps_authored_geometry_and_binding(self):
        plan = self.load(2)
        plan['plan_meta'] = {'allow_compiler_satellites': False}
        original = deepcopy(plan)
        normalize_hosted_connections(plan)
        self.assertEqual(original, plan)
