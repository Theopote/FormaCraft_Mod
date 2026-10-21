import unittest
from pathlib import Path

from app.models.building_profile import BuildingProfile
from app.services.style_visual_retriever import retrieve_visual_cases, visual_case_catalog
from app.services.vision_analyzer import VisualAnalysis, merge_visual_into_profile


class StyleVisualRetrieverTest(unittest.TestCase):
    def test_catalog_examples_exist_and_match_their_own_features(self):
        root = Path(__file__).resolve().parents[1] / 'eval/fixtures/plans'
        for case in visual_case_catalog():
            with self.subTest(case=case['id']):
                self.assertTrue((root / case['plan_fixture']).is_file())
                observations = [phrases[0] for phrases in case['feature_groups'].values()]
                hits = retrieve_visual_cases(observations, confidence=.85)
                self.assertEqual(case['id'], hits[0]['case_id'])
                self.assertEqual(1, hits[0]['score'])

    def test_weak_caption_and_single_feature_do_not_route(self):
        self.assertEqual([], retrieve_visual_cases(['pointed arches', 'flying buttresses'], confidence=.35))
        self.assertEqual([], retrieve_visual_cases(['rose window'], confidence=.9))
        self.assertEqual([], retrieve_visual_cases(['unknown silhouette'], confidence=.9))
        self.assertEqual([], retrieve_visual_cases(['no pointed arches', 'without flying buttresses'], confidence=.9))
        self.assertEqual([], retrieve_visual_cases(['没有尖拱', '无飞扶壁'], confidence=.9))

    def test_chinese_features_and_ties_remain_candidates(self):
        hits = retrieve_visual_cases(['尖拱', '飞扶壁'], confidence=.8)
        self.assertEqual('Gothic_Cathedral', hits[0]['style_id'])
        mixed = retrieve_visual_cases(['尖拱', '飞扶壁', '红砖', '钢桁架'], confidence=.8)
        self.assertEqual(2, len(mixed))
        self.assertEqual(mixed[0]['score'], mixed[1]['score'])

    def test_merge_keeps_existing_style_and_does_not_invent_evidence(self):
        profile = BuildingProfile()
        profile.identity.style = 'Modern_International'
        merged = merge_visual_into_profile(profile, VisualAnalysis(
            distinctive_elements=['pointed arches', 'flying buttresses'], confidence=.9))
        self.assertEqual('Modern_International', merged.identity.style)
        self.assertIn('[Visual style candidates]', merged.research_notes)
        self.assertEqual(profile.style_specs, merged.style_specs)
        self.assertEqual(profile.sources, merged.sources)

    def test_style_labels_and_urls_are_not_visual_features(self):
        merged = merge_visual_into_profile(BuildingProfile(), VisualAnalysis(
            building_name='pointed arches flying buttresses', style='Gothic_Cathedral', confidence=.9))
        self.assertNotIn('[Visual style candidates]', merged.research_notes)
