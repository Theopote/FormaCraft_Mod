import unittest
from app.services.building_use_intent import identify_building_uses, building_use_guidance
from app.services.building_plan_stage import build_plan_stage_user_block
from app.services.building_contract import apply_building_contract
from app.models.building_profile import BuildingProfile

class BuildingUseIntentTest(unittest.TestCase):
    def test_same_style_keeps_different_uses(self):
        for text, use in [('哥特风格住宅', 'residential'), ('哥特风格商店', 'shop'), ('哥特风格公共大厅', 'public_hall')]:
            self.assertEqual(identify_building_uses(text), [use])
            self.assertIn(use + ':', build_plan_stage_user_block(BuildingProfile(), text))

    def test_use_is_independent_of_style_and_language(self):
        for text in ['现代商店', '中世纪商店', 'Gothic shop', 'Modern SHOP']:
            self.assertEqual(identify_building_uses(text), ['shop'])
        self.assertEqual(identify_building_uses('workshop tower'), [])

    def test_mixed_uses_require_binding_without_geometry_claim(self):
        text = '左栋住宅，右栋商店'
        self.assertIn('do not apply every use to every building', building_use_guidance(text))
        result = apply_building_contract({'mode': 'build', 'components': []}, text)
        report = result['proportion_hints']['building_use_intent']
        self.assertEqual(report['scope_status'], 'requires_binding')
        self.assertFalse(report['geometry_verified'])
        self.assertEqual(result, apply_building_contract(result, text))

    def test_hollow_interior_and_user_priorities_are_preserved_in_prompt(self):
        block = build_plan_stage_user_block(BuildingProfile(), '现代住宅，室内中空，不要隔墙')
        self.assertIn('Do not add partitions or furniture', block)
        self.assertIn('Explicit user requirements and opt-outs win', block)
        self.assertNotIn('≥1 DECOR_DETAIL', block)
        self.assertIn('室内中空，不要隔墙', block)
