import json
import unittest
from copy import deepcopy
from pathlib import Path

from app.services.building_contract import apply_building_contract
from app.services.circulation_plan_normalizer import normalize_circulation_plan

ROOT = Path(__file__).parent / 'fixtures/complex_build_retest'


class LandmarkCompatibilityTest(unittest.TestCase):
    def normalize(self, plan):
        text = plan.get('proportion_hints', {}).get('architecture_intent', {}).get('original_text', '')
        return normalize_circulation_plan(apply_building_contract(plan, text, finalize=True))

    def test_logged_landmark_plans_are_valid_and_stable(self):
        for i in range(1, 8):
            with self.subTest(plan=i):
                raw = json.loads((ROOT / f'oct18_landmark_{i}.json').read_text(encoding='utf-8'))
                original = deepcopy(raw)
                result = self.normalize(raw)
                self.assertNotIn('capability_gap', result)
                self.assertEqual(result, self.normalize(result))
                self.assertEqual(result, json.loads((ROOT / f'oct18_landmark_{i}.normalized.json').read_text(encoding='utf-8')))
                self.assertEqual(original, raw)

    def test_unknown_host_remains_an_error(self):
        plan = json.loads((ROOT / 'oct18_landmark_5.json').read_text(encoding='utf-8'))
        roof = next(c for c in plan['components'] if c['params']['component_id'] == 'meridian_gate_roof')
        roof['params']['host_id'] = 'nonexistent_gate'
        result = self.normalize(plan)
        self.assertTrue(any(d['code'] == 'E_HOST_UNKNOWN' for d in result['proportion_hints']['building_contract']['diagnostics']))

    def test_floor_repair_is_bounded_and_preserves_storeys(self):
        raw = json.loads((ROOT / 'oct18_landmark_7.json').read_text(encoding='utf-8'))
        result = self.normalize(raw)
        main = result['components'][0]
        self.assertEqual(20, main['dimensions']['height'])
        self.assertEqual(5, main['params']['floor_count'])
        self.assertEqual(4, main['params']['floor_height'])
        raw['components'][0]['dimensions']['height'] = 12
        invalid = self.normalize(raw)
        self.assertEqual('E_BUILDING_CONTRACT', invalid['capability_gap']['code'])

    def test_compound_foundation_is_below_floor_and_spheres_keep_shape(self):
        palace = self.normalize(json.loads((ROOT / 'oct18_landmark_5.json').read_text(encoding='utf-8')))
        base = palace['components'][0]
        self.assertEqual(-2, base['relative_position']['y'])
        gate = next(c for c in palace['components'] if c['params']['component_id'] == 'meridian_gate')
        self.assertEqual('MASS_SECONDARY', gate['component_type'])
        self.assertEqual('meridian_gate', next(c for c in palace['components'] if c['params']['component_id'] == 'meridian_gate_roof')['params']['host_id'])
        tower = self.normalize(json.loads((ROOT / 'oct18_landmark_6.json').read_text(encoding='utf-8')))
        spheres = [c for c in tower['components'] if c['params'].get('volume_geometry') == 'sphere']
        self.assertEqual(3, len(spheres))
        self.assertEqual([22,16,8], [c['dimensions']['height'] for c in spheres])
