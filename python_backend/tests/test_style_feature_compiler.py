import unittest

from app.models.building_profile import BuildingProfile, StyleSpec, StyleFeatureEvidence
from app.services.style_feature_compiler import validate_style_evidence, compile_style_feature_defaults
from app.services.building_plan_stage import build_plan_stage_user_block


class StyleFeatureCompilerTest(unittest.TestCase):
    def profile(self, feature="flat roof", urls=None, confidence=0.9):
        return BuildingProfile(style_specs=[StyleSpec(requested_name="Example", scope="house_a",
            features=[StyleFeatureEvidence(feature=feature, scope="roof_a", confidence=confidence,
                source_urls=urls or ["https://museum.example/reference"], implementation_status="verified")])])

    def test_unretrieved_urls_cannot_supply_defaults(self):
        profile = validate_style_evidence(self.profile(), [])
        feature = profile.style_specs[0].features[0]
        self.assertEqual(feature.confidence, 0)
        self.assertEqual(feature.implementation_status, "unverified")
        self.assertEqual(compile_style_feature_defaults(profile)[0]["params"], {})

    def test_retrieved_evidence_maps_exact_parameter_and_preserves_scope(self):
        profile = validate_style_evidence(self.profile(), [{"url": "https://museum.example/reference"}])
        mapping = compile_style_feature_defaults(profile)[0]
        self.assertEqual(mapping["params"], {"roof_type": "flat"})
        self.assertEqual(mapping["style_scope"], "house_a")
        self.assertEqual(mapping["scope"], "roof_a")
        self.assertEqual(mapping["status"], "mapped_default")

    def test_unknown_and_low_confidence_features_are_not_guessed(self):
        self.assertEqual(compile_style_feature_defaults(self.profile("complex vault"))[0]["status"], "unsupported")
        self.assertEqual(compile_style_feature_defaults(self.profile(confidence=0.2))[0]["params"], {})

    def test_prompt_defaults_are_subordinate_to_user_requirements(self):
        block = build_plan_stage_user_block(self.profile(), "Use a gable roof")
        self.assertIn("only when the user has not specified", block)
        self.assertIn("Use a gable roof", block)

    def test_sources_are_rebuilt_from_actual_search_and_reject_non_web_urls(self):
        profile = validate_style_evidence(self.profile(), [{"url": "file:///secret"},
            {"url": "https://museum.example/reference", "title": "Museum"}])
        self.assertEqual([s.url for s in profile.sources], ["https://museum.example/reference"])

    def test_supported_roof_features_preserve_evidence_and_scope(self):
        for name, expected in [('hipped roof', 'hip'), ('四坡屋顶', 'hip'),
                               ('pyramid roof', 'pyramid'), ('悬山屋顶', 'xuanshan'),
                               ('hip and gable roof', 'xieshan'), ('歇山屋顶', 'xieshan')]:
            with self.subTest(name=name):
                mapping = compile_style_feature_defaults(self.profile(name))[0]
                self.assertEqual(mapping['params'], {'roof_type': expected})
                self.assertEqual(mapping['scope'], 'roof_a')
                self.assertEqual(mapping['style_scope'], 'house_a')
                self.assertEqual(compile_style_feature_defaults(self.profile(name, confidence=0.2))[0]['params'], {})

    def test_cultural_names_do_not_guess_a_specific_roof(self):
        for name in ['Chinese roof', '日式屋顶', 'temple roof', 'hip roof with elaborate dormers']:
            self.assertEqual(compile_style_feature_defaults(self.profile(name))[0]['status'], 'unsupported')
