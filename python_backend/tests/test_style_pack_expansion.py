"""Data-driven style pack coverage: extend the manifest for future packs."""
from copy import deepcopy
import json
from pathlib import Path
import unittest

from app.services.ai_planner import _fill_component_params
from app.services.building_request_classifier import classify_building_request_local
from app.services.keyword_culture_retriever import retrieve
from app.services.style_identity import canonical_style_id, negated_style_ids
from app.services.style_profile_registry import get_style_profile, catalog_prompt_block, has_palette
from app.services.typology_registry import typology_for_culture_card
from app.models.llm_plan import validate_llm_plan_dict
from eval.golden_eval import evaluate_plan

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'src/main/resources/assets/formacraft'


class StylePackExpansionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.styles = json.loads((ASSETS / 'style_profiles/style_pack_manifest_v1.json').read_text(encoding='utf-8'))['styles']

    def test_identity_profiles_and_twelve_role_genomes_agree(self):
        self.assertEqual({s['family'] for s in self.styles}, {'A', 'B', 'C', 'D', 'E'})
        for entry in self.styles:
            with self.subTest(style=entry['style_id']):
                profile = get_style_profile(entry['style_id'])
                self.assertIsNotNone(profile)
                self.assertTrue(has_palette(entry['palette_id']))
                genome = json.loads((ASSETS / 'style_genomes' / (entry['genome_id'] + '.json')).read_text(encoding='utf-8'))
                self.assertEqual(len(genome['palette']), 12)
                for key, value in genome['palette'].items():
                    self.assertIn(value, profile.defaults['materials'][key])
                for alias in entry['aliases']:
                    self.assertEqual(canonical_style_id(alias), entry['style_id'])
                    self.assertIs(get_style_profile(alias), profile)

    def test_bilingual_retrieval_and_profile_ranking_do_not_require_style_branches(self):
        for entry in self.styles:
            for prompt in entry['prompts']:
                with self.subTest(prompt=prompt):
                    hits = retrieve(prompt, topK=1, fewShotK=0)['hits']
                    self.assertEqual(hits[0]['id'], entry['culture_card_id'])
                    first = catalog_prompt_block(max_items=1, query_text=prompt).splitlines()[1]
                    self.assertTrue(first.startswith('- ' + entry['style_id'] + ' |'), first)
                    self.assertFalse(classify_building_request_local(prompt).is_specific_real_building)

    def test_whole_style_refusals_do_not_supply_culture_hits(self):
        from app.services.archetype_detector import detect_archetype_local
        generic = detect_archetype_local('生成一座粗野主义大楼')
        self.assertIn('generic_typology', generic.reason_tags)
        self.assertFalse(generic.qualifies_for_module_route())
        self.assertTrue(detect_archetype_local('build a brutalist megastructure').qualifies_for_module_route())
        from app.services.building_research_agent import _extract_subject
        self.assertEqual(_extract_subject('build an art deco tower'), 'art deco tower')
        self.assertEqual(_extract_subject('build Amsterdam museum'), 'Amsterdam museum')
        self.assertTrue(classify_building_request_local('build Amsterdam museum').is_specific_real_building)
        self.assertTrue(classify_building_request_local('build a japanese timber Osaka house').is_specific_real_building)
        for entry in self.styles:
            with self.subTest(style=entry['style_id']):
                hits = retrieve(entry['negative_prompt'], topK=20, fewShotK=0)['hits']
                self.assertNotIn(entry['culture_card_id'], [hit['id'] for hit in hits])
        self.assertIn('Art_Deco', negated_style_ids('not art deco'))
        self.assertNotIn('Gothic_Cathedral', negated_style_ids('哥特教堂，不要哥特窗花'))
        self.assertTrue(classify_building_request_local('建造文艺复兴风格的圣家族大教堂').is_specific_real_building)

    def test_catalog_defaults_reach_all_component_types_but_explicit_params_win(self):
        for entry in self.styles:
            profile = get_style_profile(entry['style_id'])
            for kind, expected in profile.defaults['component_params'].items():
                with self.subTest(style=entry['style_id'], component=kind):
                    component = {'component_type': kind, '_style_hint': entry['style_id'], 'features': []}
                    actual = {}
                    _fill_component_params(actual, component, {})
                    for key, value in expected.items():
                        self.assertEqual(actual[key], value)
                    explicit = {key: False if isinstance(value, bool) else 0 if isinstance(value, (int, float)) else 'explicit'
                                for key, value in expected.items()}
                    original = deepcopy(explicit)
                    _fill_component_params(explicit, component, {})
                    self.assertEqual({key: explicit[key] for key in original}, original)

    def test_two_goldens_and_references_per_style_are_valid(self):
        scenarios = json.loads((ROOT / 'python_backend/eval/fixtures/scenarios.json').read_text(encoding='utf-8'))
        scenario_paths = {s['plan_fixture'] for s in scenarios if s.get('ci')}
        for entry in self.styles:
            self.assertEqual(len(entry['plans']), 2)
            self.assertEqual(typology_for_culture_card(entry['culture_card_id']), entry['typology_id'])
            card = json.loads((ASSETS / 'culture_cards' / (entry['culture_card_id'] + '.json')).read_text(encoding='utf-8'))
            self.assertEqual(card['styleId'], entry['style_id'])
            for name in entry['plans']:
                with self.subTest(fixture=name):
                    plan = json.loads((ROOT / 'python_backend/eval/fixtures/plans' / name).read_text(encoding='utf-8'))
                    validate_llm_plan_dict(plan)
                    self.assertEqual(plan['style_profile'], entry['style_id'])
                    self.assertTrue(plan['proportion_hints'])
                    self.assertFalse(evaluate_plan(plan, label=name, prompt=entry['prompts'][0]).hard_failures)
                    self.assertIn('plans/' + name, scenario_paths)
                    self.assertIn(name, card['llmPlanExampleRefs'])
                    example = json.loads((ASSETS / 'llmplan_examples' / name).read_text(encoding='utf-8'))
                    self.assertEqual(example['plan'], plan)


if __name__ == '__main__':
    unittest.main()
