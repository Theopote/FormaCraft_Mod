import json
from pathlib import Path
import unittest
from app.services.building_contract import apply_building_contract

ROOT = Path(__file__).resolve().parents[2] / 'src/test/resources/style_log_cases_round5'

class StyleFeedbackTest(unittest.TestCase):
    def test_closed_gables_do_not_disable_all_windows(self):
        plan = json.loads((ROOT / 'case_5_raw.json').read_text(encoding='utf-8'))
        result = apply_building_contract(plan, '徽派住宅，只保留普通封闭山墙，入口两侧设窗户。')
        mass = next(c for c in result['components'] if c['component_type'] == 'MASS_MAIN')
        self.assertIs(False, mass['params']['gable_windows'])
        self.assertNotEqual('none', mass['params'].get('window_style'))
        requirement = next(r for r in result['proportion_hints']['building_contract']['requirements'] if r['property'] == 'gable_windows')
        self.assertEqual('planned', requirement['status'])
    def test_six_logged_plans_keep_contract_normalization_idempotent(self):
        for path in ROOT.glob('case_*_raw.json'):
            result = apply_building_contract(json.loads(path.read_text(encoding='utf-8')), '')
            self.assertEqual(result['components'], apply_building_contract(result, '')['components'])
    def test_scoped_no_windows_is_not_a_global_opt_out(self):
        plan = json.loads((ROOT / 'case_5_raw.json').read_text(encoding='utf-8'))
        for text in ('山墙不开窗，其他墙面设窗户。', '山墙不要窗户，门两侧有窗户。'):
            result = apply_building_contract(plan, text)
            mass = next(c for c in result['components'] if c['component_type'] == 'MASS_MAIN')
            self.assertIs(False, mass['params']['gable_windows'])
            self.assertNotEqual('none', mass['params'].get('window_style'))
