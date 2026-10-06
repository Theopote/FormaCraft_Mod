import unittest
from app.services.style_identity import style_identity, canonical_style_id
from app.services.style_profile_registry import get_style_profile, default_palette_for_style

class StyleIdentityTest(unittest.TestCase):
    def test_equivalent_aliases_share_profile_and_palette(self):
        self.assertIs(get_style_profile('Industrial_Structure'),get_style_profile('Industrial_Structural'))
        self.assertEqual(default_palette_for_style('Classical_GrecoRoman'),default_palette_for_style('Greco_Roman_Classical'))
    def test_chinese_alias_resolves(self):self.assertEqual('Modern_International',canonical_style_id('现代风格'))
    def test_historical_variant_retains_identity(self):
        item=style_identity('Tang_Dynasty_Timber')
        self.assertEqual('recognized_variant',item['status']);self.assertIsNone(item['profile_id'])
        self.assertEqual('tailiang_timber_hall',item['structural_typology'])
    def test_edit_is_not_architectural_style(self):self.assertEqual('non_style',style_identity('Patch_Edit')['status'])
    def test_unknown_is_not_a_modern_fallback(self):self.assertEqual('unknown',style_identity('UnknownStyle')['status'])
