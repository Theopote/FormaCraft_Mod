import json
from pathlib import Path
import unittest
from app.services.building_contract import apply_building_contract, extract_requirements

ROOT = Path(__file__).resolve().parents[2] / 'src/test/resources/terrain_log_cases'

class TerrainLogContract(unittest.TestCase):
    def test_six_actual_prompts_have_no_false_contract_conflicts(self):
        prompts=json.loads((ROOT/'prompts.json').read_text(encoding='utf-8'))
        for index,prompt in enumerate(prompts,1):
            with self.subTest(index=index):
                raw=json.loads((ROOT/f'case_{index}_raw.json').read_text(encoding='utf-8'))
                plan=apply_building_contract(raw,prompt)
                self.assertEqual([],plan['proportion_hints']['building_contract']['diagnostics'])
                self.assertFalse(plan.get('capability_gap'))
                self.assertEqual(plan['components'],apply_building_contract(plan,prompt)['components'])

    def test_left_gable_right_flat_are_independent_requirements(self):
        requirements=extract_requirements('左栋使用云杉木双坡屋顶，右栋使用白色混凝土墙和平屋顶。')
        roofs={r['scope']:r['value'] for r in requirements if r['property']=='roof_type'}
        self.assertEqual({'building_1':'gable','building_2':'flat'},roofs)

    def test_real_roof_opt_out_is_not_removed(self):
        raw=json.loads((ROOT/'case_3_raw.json').read_text(encoding='utf-8'))
        plan=apply_building_contract(raw,'不要屋顶')
        mass=next(c for c in plan['components'] if c['component_type']=='MASS_MAIN')
        self.assertEqual('none',mass['params']['roof_type'])
