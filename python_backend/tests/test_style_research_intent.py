import unittest
from unittest.mock import patch

from app.models.building_profile import RequestClassification, profile_from_llm_dict
from app.services.building_request_classifier import should_research_for_classification
from app.services.building_research_agent import plan_search_queries, research_building_profile
from app.services.style_research_intent import requested_styles


class StyleResearchIntentTest(unittest.TestCase):
    def test_unknown_style_does_not_need_landmark_identity(self):
        text = "生成苏丹萨赫勒风格住宅"
        classification = RequestClassification(is_specific_real_building=False)
        self.assertTrue(should_research_for_classification(classification, user_text=text))
        should, queries, subject = plan_search_queries(text, classification=classification)
        self.assertTrue(should)
        self.assertIn("苏丹萨赫勒", subject)
        self.assertTrue(any("facade" in q for q in queries))

    def test_ordinary_and_edit_requests_still_skip(self):
        classification = RequestClassification(is_specific_real_building=False)
        for text in ("生成一栋住宅", "把屋顶换成哥特风格"):
            self.assertFalse(should_research_for_classification(classification, user_text=text))

    def test_unknown_english_and_original_script_names_preserved(self):
        self.assertIn("Nubian", requested_styles("Build a Nubian-style house"))
        self.assertIn("ქართული", requested_styles('Build a "ქართული" style house'))

    def test_network_off_is_authoritative(self):
        with patch.dict("os.environ", {"BUILDING_RESEARCH_MODE": "off"}):
            self.assertFalse(plan_search_queries("生成苏丹萨赫勒风格住宅")[0])

    def test_profile_evidence_round_trip_and_legacy_compatibility(self):
        data = {"style_specs": [{"requested_name": "Nubian", "region": "Nubia",
                "features": [{"feature": "vaulted roof", "source_urls": ["https://example.org/reference"]}]}]}
        profile = profile_from_llm_dict(data)
        self.assertEqual(profile.to_prompt_dict()["style_specs"][0]["region"], "Nubia")
        self.assertEqual(profile.style_specs[0].features[0].implementation_status, "unverified")
        self.assertEqual(profile_from_llm_dict({"identity": {"style": "Gothic"}}).identity.style, "Gothic")

    def test_research_entry_calls_search_for_generic_unknown_style(self):
        calls = []
        with patch.dict("os.environ", {"BUILDING_RESEARCH": "on", "BUILDING_RESEARCH_MODE": "always", "BUILDING_RESEARCH_LLM_SYNTH": "off"}), patch(
            "app.services.building_request_classifier.classify_building_request",
            return_value=RequestClassification(is_specific_real_building=False),
        ):
            profile = research_building_profile("生成苏丹萨赫勒风格住宅", search_fn=lambda q, n: calls.append(q) or [])
        self.assertTrue(calls)
        self.assertIsNotNone(profile)
        self.assertEqual(profile.style_specs[0].scope, "unspecified")
        self.assertFalse(profile.style_specs[0].features)


if __name__ == "__main__":
    unittest.main()
