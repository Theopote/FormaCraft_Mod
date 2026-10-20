import unittest
from unittest.mock import patch

from app.models.building_spec import BuildingSpec, BuildingStyle
from app.services.ai_planner import _build_genome_from_spec, _fill_component_params
from app.services.chinese_material_contract import apply_chinese_material_contract
from app.services.style_feature_compiler import component_style_defaults
from app.services.style_identity import generic_style_pattern
from app.services.style_profile_registry import StyleProfileDef, StyleProfileMeta


class StyleCatalogP0Test(unittest.TestCase):
    def test_legacy_genome_values_remain_compatible(self):
        for style, expected in [(BuildingStyle.ASIAN, ('chinese', 'traditional')),
                                (BuildingStyle.MEDIEVAL, ('european', 'medieval')),
                                (BuildingStyle.MODERN, ('modern', 'modern')),
                                (BuildingStyle.FUTURISTIC, ('modern', 'modern')),
                                (BuildingStyle.RUSTIC, ('rustic', 'traditional')),
                                (BuildingStyle.DEFAULT, (None, None))]:
            with self.subTest(style=style):
                spec = BuildingSpec(type='HOUSE', style=style, footprint={}, materials={}, features={})
                cultural = _build_genome_from_spec(None, spec).culturalStyle
                self.assertEqual((cultural.region, cultural.era), expected)

    def test_new_profile_flows_into_planner_without_style_branches(self):
        profile = StyleProfileDef('NewStyle', StyleProfileMeta(), {
            'component_params': {'MASS_MAIN': {'facade_profile': 'module_grid'}}}, {})
        with patch('app.services.style_profile_registry.get_style_profile', return_value=profile):
            params = {}
            _fill_component_params(params, {'component_type': 'MASS_MAIN', '_style_hint': 'NewStyle'}, {})
            self.assertEqual(params['facade_profile'], 'module_grid')
            explicit = {'facade_profile': 'plain'}
            _fill_component_params(explicit, {'component_type': 'MASS_MAIN', '_style_hint': 'NewStyle'}, {})
            self.assertEqual(explicit['facade_profile'], 'plain')

    def test_known_facades_and_unknown_style(self):
        for style, expected in [('Gothic_Cathedral', 'vertical_pilasters'),
                                ('French', 'vertical_pilasters'),
                                ('MODERN', 'mullion_grid'), ('Industrial', 'mullion_grid')]:
            with self.subTest(style=style):
                params = {}
                _fill_component_params(params, {'component_type': 'MASS_MAIN', '_style_hint': style}, {})
                self.assertEqual(params['facade_profile'], expected)
        self.assertEqual(component_style_defaults('Unknown_Style', 'MASS_MAIN'), {})
        self.assertEqual(component_style_defaults('Gothic_Cathedral', 'ENTRANCE'), {})

    def test_aliases_are_literal_and_english_spacing_is_flexible(self):
        with patch('app.services.style_identity.identity_catalog', return_value={
                'lexical_aliases': {'NewStyle': ['新风格', 'a+b', '']},
                'generic_style_markers': ['art deco']}):
            pattern = generic_style_pattern()
            self.assertIsNotNone(pattern.search('建造新风格神庙'))
            self.assertIsNotNone(pattern.search('ARTDECO'))
            self.assertIsNotNone(pattern.search('art   deco'))
            self.assertIsNotNone(pattern.search('a+b'))
            self.assertIsNone(pattern.search('aaab'))
            self.assertIsNone(pattern.search('anything'))

    def test_material_catalog_extension_and_negation(self):
        def plan():
            return {'components': [{'component_type': 'STRUCTURE',
                'params': {'component_id': 'hall', 'typology_id': 'tailiang_timber_hall'}}]}
        with patch('app.services.chinese_material_contract.material_aliases',
                   return_value={'新材料': 'minecraft:sandstone'}):
            positive = plan()
            apply_chinese_material_contract(positive, '墙体使用新材料')
            self.assertEqual(positive['components'][0]['params']['wall_block'], 'minecraft:sandstone')
            negative = plan()
            apply_chinese_material_contract(negative, '不要墙体使用新材料')
            self.assertNotIn('wall_block', negative['components'][0]['params'])


if __name__ == '__main__':
    unittest.main()
