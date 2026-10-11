import unittest
from copy import deepcopy
from app.services.building_contract import apply_building_contract
from app.services.chinese_material_contract import apply_chinese_material_contract


class ChineseMaterialContractTest(unittest.TestCase):
    def hall(self):
        return {'components': [{'component_type': 'STRUCTURE',
                                'params': {'typology_id': 'tailiang_timber_hall', 'component_id': 'hall'}}]}

    def test_explicit_materials_and_paint_opt_out_pass_through_contract(self):
        plan=apply_building_contract(self.hall(), '墙体使用白色混凝土，屋面采用minecraft:bricks，柱子使用橡木原木，不要彩画。', finalize=True)
        params=plan['components'][0]['params']
        self.assertEqual('minecraft:white_concrete',params['wall_block'])
        self.assertEqual('minecraft:bricks',params['roof_block'])
        self.assertEqual('minecraft:oak_log',params['pillar_block'])
        self.assertFalse(params['includePainting'])
        self.assertNotIn('capability_gap',plan)

    def test_paint_material_does_not_enable_paint_and_brackets_are_independent(self):
        plan=self.hall()
        plan['components'][0]['params']['includePainting']=False
        apply_chinese_material_contract(plan,'彩画采用蓝色陶瓦，设置斗拱。')
        params=plan['components'][0]['params']
        self.assertEqual('minecraft:blue_terracotta',params['paint_block'])
        self.assertFalse(params['includePainting'])
        self.assertTrue(params['includeBrackets'])

    def test_conflicts_and_scopes_do_not_apply_partial_updates(self):
        for text in ['设置彩画，不要彩画', '第一栋墙体使用石砖', '墙体使用石砖，墙体使用白色混凝土']:
            plan=self.hall();before=deepcopy(plan['components'])
            apply_chinese_material_contract(plan,text)
            self.assertEqual(before,plan['components'])
            self.assertEqual('E_CHINESE_MATERIAL_CONTRACT',plan['capability_gap']['code'])
        plan=self.hall();plan['components'].append(deepcopy(plan['components'][0]))
        apply_chinese_material_contract(plan,'不要彩画')
        self.assertEqual('E_CHINESE_MATERIAL_CONTRACT',plan['capability_gap']['code'])

    def test_negated_materials_and_other_routes_are_preserved(self):
        plan=self.hall()
        apply_chinese_material_contract(plan,'不要墙体使用石砖')
        self.assertNotIn('wall_block',plan['components'][0]['params'])
        other={'components':[{'component_type':'MASS_MAIN','params':{}}]}
        before=deepcopy(other)
        apply_chinese_material_contract(other,'不要彩画')
        self.assertEqual(before,other)
