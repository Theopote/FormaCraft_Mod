import json
from pathlib import Path
import unittest
from app.services.building_contract import apply_building_contract

ROOT = Path(__file__).resolve().parents[2] / 'src/test/resources/style_log_cases_round3'

class RoundThree(unittest.TestCase):
    def test_translated_host_components_remain_in_host_frame_after_revalidation(self):
        plan = json.loads((ROOT / 'case_2.json').read_text(encoding='utf-8'))
        result = apply_building_contract(plan, '生成一栋两层现代住宅，宽15格、深13格，保留入口和窗户')
        self.assertEqual([], result['proportion_hints']['building_contract']['diagnostics'])
        body = next(c for c in result['components'] if c['component_type'] == 'MASS_MAIN')
        for c in result['components']:
            if c['params'].get('host_id') == body['params']['component_id']:
                self.assertEqual(body['slot_id'], c['slot_id'])
                self.assertEqual(next(x for x in plan['components'] if x['params']['component_id'] == c['params']['component_id'])['relative_position'], c['relative_position'])

    def test_actual_failed_log_rebases_all_five_host_attachments(self):
        plan = json.loads((ROOT / 'case_2_raw.json').read_text(encoding='utf-8'))
        plan.pop('capability_gap', None); plan.pop('plan_status', None)
        result = apply_building_contract(plan, '生成一栋两层现代住宅，宽15格、深13格，保留入口和窗户')
        self.assertEqual([], result['proportion_hints']['building_contract']['diagnostics'])
        attached = [c for c in result['components'] if c['params'].get('host_id') == 'mass_main']
        self.assertEqual(5, len(attached))
        self.assertTrue(all(c['slot_id'] == 'slot_1' for c in attached))
        again = apply_building_contract(result, '')
        self.assertEqual([c['relative_position'] for c in attached],
                         [c['relative_position'] for c in again['components'] if c['params'].get('host_id') == 'mass_main'])
