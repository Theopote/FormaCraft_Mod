import unittest
from app.services.architecture_intent import describe_architecture, STYLE_ALIASES
from app.services.style_profile_registry import has_style_profile
from app.services.building_contract import apply_building_contract

class ArchitectureIntentTest(unittest.TestCase):
    def test_purpose_style_and_structure_are_independent(self):
        scope=describe_architecture('现代风格图书馆，采用钢框架')['scopes'][0]
        self.assertEqual(['library'],[v['id'] for v in scope['purposes']])
        self.assertEqual(['Modern_International'],[v['id'] for v in scope['styles']])
        self.assertEqual(['frame'],[v['id'] for v in scope['structures']])

    def test_material_does_not_imply_style(self):
        self.assertEqual([],describe_architecture('白色混凝土住宅，设置玻璃窗')['scopes'][0]['styles'])

    def test_style_does_not_imply_purpose(self):
        self.assertEqual([],describe_architecture('哥特式建筑')['scopes'][0]['purposes'])

    def test_negative_and_part_scopes_are_retained(self):
        values=describe_architecture('不要哥特式，主体采用现代风格，屋顶采用日式传统')['scopes'][0]['styles']
        self.assertEqual(['excluded','requested','requested'],[v['status'] for v in values])
        self.assertEqual(['building','主体','屋顶'],[v['part'] for v in values])

    def test_separate_buildings_keep_separate_styles(self):
        scopes=describe_architecture('两栋住宅，左栋采用徽派，右栋采用现代风格')['scopes']
        self.assertEqual(['global','左栋','右栋'],[s['scope'] for s in scopes])
        self.assertEqual('Chinese_Vernacular_Huizhou',scopes[1]['styles'][0]['id'])
        self.assertEqual('Modern_International',scopes[2]['styles'][0]['id'])

    def test_aliases_reference_existing_catalog_entries(self):
        from app.services.style_identity import style_identity
        self.assertTrue(all(has_style_profile(key) or style_identity(key)['status']=='recognized_variant' for key in STYLE_ALIASES))

    def test_contract_enrichment_does_not_rewrite_components(self):
        plan={'mode':'build','components':[]}
        result=apply_building_contract(plan,'现代风格图书馆')
        self.assertEqual([],result['components'])
        self.assertEqual(result,apply_building_contract(result,'现代风格图书馆'))

    def test_unknown_style_remains_in_original_scope_text(self):
        scope=describe_architecture('建造一栋未知星云流派住宅')['scopes'][0]
        self.assertEqual([],scope['styles']);self.assertIn('未知星云流派',scope['text'])

    def test_english_words_keep_boundaries(self):
        scope=describe_architecture('Modern_International library')['scopes'][0]
        self.assertEqual('Modern_International',scope['styles'][0]['id'])
        self.assertEqual('library',scope['purposes'][0]['id'])

    def test_material_requirement_is_copied_without_new_defaults(self):
        result=apply_building_contract({'mode':'build','components':[]},'现代风格图书馆，墙体使用石砖')
        materials=result['proportion_hints']['architecture_intent']['materials']
        self.assertEqual(['minecraft:stone_bricks'],[r['value'] for r in materials])
