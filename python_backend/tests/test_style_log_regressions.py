import json
from pathlib import Path
import unittest
from app.services.building_contract import apply_building_contract, extract_requirements

ROOT = Path(__file__).resolve().parents[2] / 'src/test/resources/style_log_cases'

class StyleLogRegressions(unittest.TestCase):
    def test_ordinal_is_declarations(self):
        reqs = extract_requirements('第一栋是白色混凝土现代平顶住宅，不要复杂装饰；第二栋是石砖墙、云杉木山墙屋顶的传统住宅。两栋都保留窗户和入口。')
        self.assertTrue(any(r['property'] == 'roof_type' and r['scope'] == 'building_1' for r in reqs))
        self.assertFalse(any(r['property'] == 'roof_type' and r['scope'] == 'all_main_masses' for r in reqs))

    def test_nested_coordinate_frames_bind_correct_foundation(self):
        plan = json.loads((ROOT / 'case_4.json').read_text(encoding='utf-8'))
        # Remove previous erroneous inferred ownership before replaying raw normalization.
        foundation = next(c for c in plan['components'] if c['params']['component_id'] == 'house_b_foundation')
        foundation['params'].pop('host_id')
        result = apply_building_contract(plan, '生成两栋住宅')
        foundation = next(c for c in result['components'] if c['params']['component_id'] == 'house_b_foundation')
        self.assertEqual('slot_1', foundation['slot_id'])
        self.assertEqual('house_b_mass', foundation['params']['host_id'])

    def test_legacy_delegated_roof_and_user_optout(self):
        plan = json.loads((ROOT / 'case_1.json').read_text(encoding='utf-8'))
        result = apply_building_contract(plan, '墙体使用石砖，屋顶使用云杉木板')
        body = next(c for c in result['components'] if c['component_type'] == 'MASS_MAIN')
        self.assertEqual('gable', body['params']['roof_type'])
        result = apply_building_contract(plan, '不要屋顶')
        body = next(c for c in result['components'] if c['component_type'] == 'MASS_MAIN')
        self.assertEqual('none', body['params']['roof_type'])

    def test_mixed_roof_log_passes_correct_scopes(self):
        plan = json.loads((ROOT / 'case_5.json').read_text(encoding='utf-8'))
        plan.pop('capability_gap', None)
        plan.pop('plan_status', None)
        text = '生成两栋并排住宅。第一栋是白色混凝土现代平顶住宅，不要复杂装饰；第二栋是石砖墙、云杉木山墙屋顶的传统住宅，允许适量窗套和屋檐装饰。两栋都保留窗户和入口。'
        result = apply_building_contract(plan, text, finalize=True)
        self.assertNotIn('capability_gap', result)
