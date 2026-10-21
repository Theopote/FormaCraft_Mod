import unittest

from app.services.building_contract import extract_requirements
from app.services.style_feature_compiler import SUPPORTED_ROOF_DEFAULTS


class P2RoofContractTests(unittest.TestCase):
    def test_bilingual_explicit_roofs(self):
        for canonical, alias in [('gambrel', '折线双坡顶'), ('barrel_vault', '筒拱屋顶'),
                                 ('onion_dome', '洋葱穹顶'), ('steep_thatch', '陡坡茅草顶')]:
            for text in (alias, canonical.replace('_', ' ')):
                with self.subTest(text=text):
                    roofs = [r for r in extract_requirements(text) if r.get('property') == 'roof_type']
                    self.assertTrue(any(r.get('value') == canonical for r in roofs), roofs)
                    self.assertIn(canonical, SUPPORTED_ROOF_DEFAULTS)

    def test_negation_does_not_enable_specialty_roofs(self):
        for text in ('不要洋葱穹顶', '不用筒拱顶', 'without onion dome', 'no barrel vault',
                     'avoid gambrel roof', 'not steep thatch'):
            with self.subTest(text=text):
                self.assertFalse(any(r.get('value') in {'gambrel', 'barrel_vault', 'onion_dome', 'steep_thatch'}
                                     for r in extract_requirements(text)))

    def test_incompatible_roofs_remain_unresolved(self):
        roofs = [r for r in extract_requirements('筒拱顶和洋葱穹顶') if r.get('property') == 'roof_type']
        self.assertEqual(2, len(roofs))
        self.assertTrue(all(r['status'] == 'unresolved' and r['value'] is None for r in roofs))
