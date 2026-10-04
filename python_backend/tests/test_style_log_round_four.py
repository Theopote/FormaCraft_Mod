import json
from pathlib import Path
import unittest
from app.services.building_contract import apply_building_contract
ROOT = Path(__file__).resolve().parents[2] / 'src/test/resources/style_log_cases_round4'

class RoundFour(unittest.TestCase):
    def test_duplicated_building_slot_centers_are_applied_once(self):
        plan = json.loads((ROOT/'case_4_raw.json').read_text(encoding='utf-8'))
        result = apply_building_contract(plan, '两栋间隔15格')
        masses = [c for c in result['components'] if c['component_type']=='MASS_MAIN']
        self.assertEqual([0,0], [c['relative_position']['x'] for c in masses])
        slots = {s['slot_id']:s for s in result['layout']['slots']}
        centers = [c['relative_position']['x'] + slots[c['slot_id']]['anchor']['x'] for c in masses]
        self.assertEqual(15, centers[1]-centers[0]-15)
        again = apply_building_contract(result, '')
        self.assertEqual(result['components'], again['components'])

    def test_legitimate_min_corner_offsets_are_not_removed(self):
        plan = json.loads((ROOT/'case_5_raw.json').read_text(encoding='utf-8'))
        result = apply_building_contract(plan, '')
        self.assertEqual([c['relative_position'] for c in plan['components']],
                         [c['relative_position'] for c in result['components']])
