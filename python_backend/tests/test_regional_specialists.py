"""Routing boundaries for newly authored regional specialist examples."""
import unittest
from app.services.building_request_classifier import classify_building_request_local
from app.services.keyword_culture_retriever import retrieve
from app.services.style_identity import canonical_style_id


class RegionalSpecialistsTest(unittest.TestCase):
    def test_jiangnan_family_is_generic_but_named_towns_remain_specific(self):
        for prompt in ('建造江南水乡住宅', 'build a jiangnan water town'):
            self.assertFalse(classify_building_request_local(prompt).is_specific_real_building, prompt)
        for prompt in ('建造乌镇', 'build zhouzhuang water town'):
            self.assertTrue(classify_building_request_local(prompt).is_specific_real_building, prompt)

    def test_new_styles_keep_separate_identity_and_refusals(self):
        for name, sid, card in [('罗马风','Romanesque','romanesque_hall'),('都铎','Tudor','tudor_house')]:
            self.assertEqual(canonical_style_id(name), sid)
            self.assertEqual(retrieve('建造'+name,topK=1)['hits'][0]['id'],card)
            self.assertNotIn(card,[hit['id'] for hit in retrieve('不要'+name,topK=100)['hits']])
