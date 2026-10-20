from copy import deepcopy
from itertools import permutations
import json
from pathlib import Path
import unittest

from pydantic import ValidationError
from app.models.building_profile import BuildingProfile, StyleSpec, StyleFeatureEvidence, profile_from_llm_dict
from app.services.style_feature_compiler import apply_style_feature_defaults, validate_style_evidence
from app.services.hosted_style_enrichment import enrich_hosted_styles


class StyleWeightTest(unittest.TestCase):
    def test_mixed_garden_chapel_fixture_through_both_enrichment_stages(self):
        fixture = json.loads((Path(__file__).resolve().parents[1] / 'eval/fixtures/style_mix_garden_chapel.json').read_text(encoding='utf-8'))
        profile = BuildingProfile.model_validate(fixture['profile'])
        before = deepcopy(fixture['plan'])
        result = apply_style_feature_defaults(enrich_hosted_styles(fixture['plan'], profile), profile)
        for host, expected in fixture['expected_roofs'].items():
            self.assertEqual(self.roof(result, host)['roof_type'], expected)
        self.assertEqual(fixture['plan'], before)
        for a, b in zip(before['components'], result['components']):
            self.assertEqual(a['relative_position'], b['relative_position'])
            self.assertEqual(a['dimensions'], b['dimensions'])
        hosts = result['proportion_hints']['hosted_style_enrichment']['hosts']
        self.assertEqual([h['style_id'] for h in hosts], ['Chinese_Imperial_Official', 'Gothic_Cathedral'])
        self.assertIn('lower_weight_default', [r['status'] for r in self.reports(result)])

    def plan(self):
        return {'components': [
            {'component_type': kind, 'params': params}
            for host in ('garden', 'chapel')
            for kind, params in [('MASS_MAIN', {'component_id': host}),
                                 ('ROOF', {'component_id': host + '_roof', 'host_id': host})]]}

    def spec(self, style, feature, weight=None, scope='garden'):
        return StyleSpec(requested_name=style, scope=scope, weight=weight, features=[
            StyleFeatureEvidence(feature=feature, confidence=.9, source_urls=['https://example.org/style'])])

    def roof(self, result, host='garden'):
        return next(c['params'] for c in result['components'] if c['params']['component_id'] == host + '_roof')

    def reports(self, result):
        return result['proportion_hints']['style_feature_report']['features']

    def test_weight_schema_and_profile_roundtrip(self):
        for value in (-.1, 1.1, float('nan'), float('inf')):
            with self.subTest(value=value), self.assertRaises(ValidationError):
                StyleSpec(weight=value)
        profile = BuildingProfile(style_specs=[self.spec('Modern_International', 'flat roof', .75)])
        self.assertEqual(profile_from_llm_dict(profile.to_prompt_dict()).style_specs[0].weight, .75)
        self.assertIsNone(StyleSpec().weight)

    def test_higher_weight_wins_independently_of_order_and_host(self):
        specs = [self.spec('Modern_International', 'flat roof', .8),
                 self.spec('Gothic_Cathedral', 'gable roof', .2),
                 self.spec('Gothic_Cathedral', 'gable roof', .1, 'chapel')]
        plan = self.plan()
        before = deepcopy(plan)
        for order in permutations(specs):
            profile = BuildingProfile(style_specs=list(order))
            result = apply_style_feature_defaults(plan, profile)
            self.assertEqual(self.roof(result)['roof_type'], 'flat')
            self.assertEqual(self.roof(result, 'chapel')['roof_type'], 'gable')
            self.assertIn('lower_weight_default', [r['status'] for r in self.reports(result)])
            self.assertEqual(result['components'], apply_style_feature_defaults(result, profile)['components'])
        self.assertEqual(plan, before)

    def test_equal_weights_preserve_conflict_and_zero_disables(self):
        for weight in (None, .5):
            profile = BuildingProfile(style_specs=[self.spec('one', 'flat roof', weight), self.spec('two', 'gable roof', weight)])
            result = apply_style_feature_defaults(self.plan(), profile)
            self.assertNotIn('roof_type', self.roof(result))
            self.assertTrue(all(r['status'] == 'conflicting_defaults' for r in self.reports(result)))
        result = apply_style_feature_defaults(self.plan(), BuildingProfile(style_specs=[self.spec('off', 'flat roof', 0)]))
        self.assertNotIn('roof_type', self.roof(result))
        self.assertEqual(self.reports(result)[0]['status'], 'disabled_weight')

    def test_priority_cannot_bypass_evidence_gate(self):
        high = self.spec('one', 'flat roof', 1).model_copy(update={'features': [
            StyleFeatureEvidence(feature='flat roof', confidence=.99, source_urls=['https://unretrieved.org/'])]})
        profile = validate_style_evidence(BuildingProfile(style_specs=[high, self.spec('two', 'gable roof', .2)]),
                                         [{'url': 'https://example.org/style'}])
        result = apply_style_feature_defaults(self.plan(), profile)
        self.assertEqual(self.roof(result)['roof_type'], 'gable')
        self.assertEqual(self.reports(result)[0]['status'], 'insufficient_evidence')

    def test_user_requirement_and_existing_params_keep_priority(self):
        profile = BuildingProfile(style_specs=[self.spec('one', 'flat roof', 1)])
        plan = self.plan()
        self.roof(plan)['roof_type'] = 'hip'
        result = apply_style_feature_defaults(plan, profile)
        self.assertEqual(self.roof(result)['roof_type'], 'hip')
        self.assertEqual(self.reports(result)[0]['status'], 'existing_parameter_conflict')
        plan = self.plan()
        plan['proportion_hints'] = {'building_contract': {'requirements': [
            {'property': 'roof_type', 'source': 'user_explicit', 'scope': 'plan', 'value': 'hip'}]}}
        result = apply_style_feature_defaults(plan, profile)
        self.assertNotIn('roof_type', self.roof(result))
        self.assertEqual(self.reports(result)[0]['status'], 'user_override')

    def test_hosted_catalog_uses_same_priority_with_host_isolation(self):
        specs = [self.spec('Modern_International', 'flat roof', .8),
                 self.spec('Gothic_Cathedral', 'gable roof', .2),
                 self.spec('Renaissance_Classical', 'hip roof', .1, 'chapel')]
        for order in permutations(specs):
            result = enrich_hosted_styles(self.plan(), BuildingProfile(style_specs=list(order)))
            reports = result['proportion_hints']['hosted_style_enrichment']['hosts']
            self.assertEqual([r['style_id'] for r in reports], ['Modern_International', 'Renaissance_Classical'])
            self.assertEqual(self.roof(result)['roof_type'], 'flat')
            self.assertEqual(self.roof(result, 'chapel')['roof_type'], 'hip')

    def test_hosted_ties_remain_conflicts_and_local_style_wins(self):
        profile = BuildingProfile(style_specs=[self.spec('Modern_International', 'flat roof', .5),
                                              self.spec('Gothic_Cathedral', 'gable roof', .5)])
        result = enrich_hosted_styles(self.plan(), profile)
        self.assertEqual(result['proportion_hints']['hosted_style_enrichment']['hosts'][0]['status'], 'conflicting_scoped_styles')
        plan = self.plan()
        plan['components'][0]['params']['style_profile'] = 'Renaissance_Classical'
        self.assertEqual(self.roof(enrich_hosted_styles(plan, profile))['roof_type'], 'hip')


if __name__ == '__main__':
    unittest.main()
