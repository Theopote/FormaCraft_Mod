from copy import deepcopy
import json
from pathlib import Path
import unittest

from app.models.building_profile import BuildingProfile, StyleSpec, StyleFeatureEvidence
from app.models.llm_plan import validate_llm_plan_dict
from app.services.building_request_classifier import classify_building_request_local
from app.services.keyword_culture_retriever import retrieve
from app.services.style_feature_compiler import validate_style_evidence, compile_style_feature_defaults
from app.services.typology_registry import typology_for_culture_card
from eval.golden_eval import evaluate_plan
from eval.diversity_eval import evaluate_diversity, DiversityThresholds

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'src/main/resources/assets/formacraft'
FIXTURES = ROOT / 'python_backend/eval/fixtures'


class StyleP1AcceptanceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.styles = json.loads((ASSETS/'style_profiles/style_pack_manifest_v1.json').read_text(encoding='utf-8'))['styles']
        cls.cases = [case for style in cls.styles for case in [style, *[
            {**style, **variant} for variant in style.get('variant_cards', [])]]]

    def test_coverage_targets_and_manifest_ownership(self):
        self.assertGreaterEqual(len(list((ASSETS/'style_genomes').glob('*.json'))), 20)
        self.assertGreaterEqual(len(list((ASSETS/'culture_cards').glob('*.json'))), 45)
        typologies = json.loads((ASSETS/'structural_typologies/structural_typologies_v1.json').read_text(encoding='utf-8'))['typologies']
        self.assertGreaterEqual(len(typologies), 16)
        self.assertEqual(len({entry['id'] for entry in typologies}), len(typologies))
        cards = [entry['culture_card_id'] for entry in self.cases]
        self.assertEqual(len(cards), len(set(cards)))
        plans = [name for case in self.cases for name in case['plans']]
        plans += [name for style in self.styles for name in style.get('diversity_plans', [])]
        self.assertEqual(len(plans), len(set(plans)))

    def test_every_program_variant_routes_bilingually_and_honors_style_refusal(self):
        for case in self.cases:
            for prompt in case['prompts']:
                with self.subTest(card=case['culture_card_id'], prompt=prompt):
                    self.assertEqual(retrieve(prompt, topK=1, fewShotK=0)['hits'][0]['id'], case['culture_card_id'])
                    self.assertFalse(classify_building_request_local(prompt).is_specific_real_building)
            hits = retrieve(case['negative_prompt'], topK=100, fewShotK=0)['hits']
            self.assertNotIn(case['culture_card_id'], [hit['id'] for hit in hits])

    def test_all_variant_references_schemas_goldens_and_proportions(self):
        scenarios = json.loads((FIXTURES/'scenarios.json').read_text(encoding='utf-8'))
        registered = {scenario['plan_fixture'] for scenario in scenarios if scenario.get('ci')}
        for case in self.cases:
            card = json.loads((ASSETS/'culture_cards'/(case['culture_card_id']+'.json')).read_text(encoding='utf-8'))
            self.assertEqual(card['styleId'], case['style_id'])
            self.assertEqual(card['proportionCardId'], case['proportion_card_id'])
            self.assertEqual(typology_for_culture_card(case['culture_card_id']), case['typology_id'])
            self.assertTrue((ASSETS/'proportion_cards'/(case['proportion_card_id']+'.json')).is_file())
            for name in case['plans']:
                with self.subTest(fixture=name):
                    plan = json.loads((FIXTURES/'plans'/name).read_text(encoding='utf-8'))
                    validate_llm_plan_dict(plan)
                    self.assertEqual(plan['style_profile'], case['style_id'])
                    self.assertEqual(plan['proportion_hints']['proportion_card_id'], case['proportion_card_id'])
                    self.assertIn(name, card['llmPlanExampleRefs'])
                    self.assertIn('plans/'+name, registered)
                    self.assertEqual(json.loads((ASSETS/'llmplan_examples'/name).read_text(encoding='utf-8'))['plan'], plan)
                    self.assertFalse(evaluate_plan(plan, label=name, prompt=case['prompts'][0]).hard_failures)

    def test_program_variants_change_envelope_not_just_names(self):
        for style in self.styles:
            base = [json.loads((FIXTURES/'plans'/name).read_text(encoding='utf-8')) for name in style['plans']]
            base_dimensions = [plan['components'][0]['dimensions'] for plan in base]
            for variant in style.get('variant_cards', []):
                for name in variant['plans']:
                    plan = json.loads((FIXTURES/'plans'/name).read_text(encoding='utf-8'))
                    self.assertNotIn(plan['components'][0]['dimensions'], base_dimensions, name)

    def test_every_style_retains_evidence_gating(self):
        for style in self.styles:
            profile = BuildingProfile(style_specs=[StyleSpec(requested_name=style['style_id'], weight=1,
                features=[StyleFeatureEvidence(feature='flat roof', confidence=.99,
                    source_urls=['https://not-retrieved.example/style'])])])
            validated = validate_style_evidence(profile, [])
            feature = validated.style_specs[0].features[0]
            self.assertEqual(feature.confidence, 0)
            self.assertEqual(feature.implementation_status, 'unverified')
            self.assertEqual(compile_style_feature_defaults(validated)[0]['params'], {})

    def test_three_family_diversity_is_real_and_duplicates_fail(self):
        scenarios = json.loads((FIXTURES/'diversity_scenarios.json').read_text(encoding='utf-8'))
        selected = [scenario for scenario in scenarios if scenario['id'].startswith('style_family_')]
        self.assertEqual({scenario['id'] for scenario in selected},
                         {'style_family_A_diversity', 'style_family_B_diversity', 'style_family_C_diversity'})
        for scenario in selected:
            plans = [json.loads((FIXTURES/name).read_text(encoding='utf-8')) for name in scenario['plan_fixtures']]
            metrics = evaluate_diversity(plans, thresholds=DiversityThresholds(**scenario['thresholds']))
            self.assertTrue(all(check.passed for check in metrics.checks), metrics)
            duplicated = evaluate_diversity([plans[0], deepcopy(plans[0])],
                thresholds=DiversityThresholds(min_samples=2, min_unique_ratio=1.0))
            self.assertFalse(next(check.passed for check in duplicated.checks if check.name == 'unique_ratio'))

    def test_mixed_host_golden_keeps_scope_bindings_and_is_in_ci(self):
        plan = json.loads((FIXTURES/'plans/mixed_garden_chapel.json').read_text(encoding='utf-8'))
        validate_llm_plan_dict(plan)
        roofs = {component['params']['host_id']: component['params']['roof_type']
                 for component in plan['components'] if component['component_type'] == 'ROOF'}
        self.assertEqual(roofs, {'garden':'hip', 'chapel':'gable'})
        self.assertFalse(evaluate_plan(plan, label='mixed', prompt='中式园林里放哥特小教堂').hard_failures)
        scenarios = json.loads((FIXTURES/'scenarios.json').read_text(encoding='utf-8'))
        self.assertTrue(any(scenario.get('ci') and scenario['plan_fixture']=='plans/mixed_garden_chapel.json'
                            for scenario in scenarios))


if __name__ == '__main__':
    unittest.main()
