import json
from pathlib import Path
import unittest
from app.services.building_contract import apply_building_contract, extract_requirements
from app.services.circulation_plan_normalizer import normalize_circulation_plan

ROOT = Path(__file__).resolve().parents[2] / 'src/test/resources/style_log_cases_round2'

class RoundTwo(unittest.TestCase):
    def test_timber_stone_storeys_and_zero_landing(self):
        text = '生成一栋两层木石住宅，宽15格、深13格。墙体使用石砖，山墙屋顶使用云杉木板，屋檐和楼梯使用对应的云杉木材料。每层设置规则窗户，正面中央设置入口，装饰适量。'
        self.assertTrue(any(r['property']=='floor_count' and r['value']==2 for r in extract_requirements(text)))
        plan = json.loads((ROOT/'case_1.json').read_text(encoding='utf-8'))
        plan.pop('capability_gap', None); plan.pop('plan_status', None)
        result = normalize_circulation_plan(apply_building_contract(plan,text))
        self.assertNotIn('capability_gap',result)
        body = next(c for c in result['components'] if c['component_type']=='MASS_MAIN')
        self.assertNotEqual('none',body['params'].get('window_style'))
        self.assertNotEqual('none',body['params'].get('entrance_type'))

    def test_scoped_materials_do_not_become_global_id(self):
        plan=json.loads((ROOT/'case_5.json').read_text(encoding='utf-8'))
        result=apply_building_contract(plan,'第一栋是现代平顶住宅；第二栋是传统住宅')
        self.assertNotIn('wall_material',result['style_attributes'])
        self.assertNotIn('floor_material',result['style_attributes'])
        self.assertEqual('white_concrete',result['components'][0]['params']['wall_block'])
