"""Stage-1 request classification — specific real building vs generic typology."""

from __future__ import annotations

import unittest
from unittest.mock import MagicMock, patch

from app.models.building_profile import RequestClassification
from app.services.building_request_classifier import (
    classify_building_request,
    classify_building_request_local,
    should_research_for_classification,
)
from app.services.building_research_agent import (
    ensure_building_profile_for_plan,
    plan_search_queries,
)


class RequestClassificationLocalTest(unittest.TestCase):
    def test_sagrada_is_specific_real_building(self):
        rc = classify_building_request_local("生成圣家族大教堂")
        self.assertTrue(rc.is_specific_real_building)
        self.assertGreaterEqual(rc.confidence, 0.7)

    def test_generic_cathedral_is_not_specific(self):
        rc = classify_building_request_local("生成一个大教堂")
        self.assertFalse(rc.is_specific_real_building)

    def test_gothic_style_church_is_generic(self):
        rc = classify_building_request_local("帮我建一个哥特风格教堂")
        self.assertFalse(rc.is_specific_real_building)

    def test_explicit_gothic_cathedral_phrase_is_specific(self):
        rc = classify_building_request_local("哥特大教堂")
        self.assertTrue(rc.is_specific_real_building)

    def test_patch_edit_is_not_specific(self):
        rc = classify_building_request_local("把屋顶换成红色")
        self.assertFalse(rc.is_specific_real_building)

    def test_sydney_opera_house_is_specific(self):
        rc = classify_building_request_local("recreate the Sydney Opera House")
        self.assertTrue(rc.is_specific_real_building)
        self.assertIn("Sydney", rc.building_name_normalized or "")


class PlanSearchQueriesClassificationTest(unittest.TestCase):
    def test_generic_cathedral_skips_search(self):
        should, queries, _ = plan_search_queries("生成一个大教堂")
        self.assertFalse(should)
        self.assertEqual(queries, [])

    def test_sagrada_still_searches(self):
        should, queries, subject = plan_search_queries("建造圣家堂")
        self.assertTrue(should)
        self.assertGreaterEqual(len(queries), 1)
        self.assertTrue(subject)

    def test_should_research_with_references_even_if_generic(self):
        rc = RequestClassification(is_specific_real_building=False)
        self.assertTrue(should_research_for_classification(rc, has_references=True))


class EnsureProfileForPlanTest(unittest.TestCase):
    def test_generic_typology_returns_none_without_research(self):
        profile = ensure_building_profile_for_plan("生成一个大教堂", None)
        self.assertIsNone(profile)

    def test_specific_building_gets_stub_when_research_missing(self):
        profile = ensure_building_profile_for_plan("建造圣家堂", None)
        self.assertIsNotNone(profile)
        self.assertTrue(profile.request_classification is None or profile.request_classification.is_specific_real_building)


class RequestClassificationLlmTest(unittest.TestCase):
    def test_llm_result_used_when_confident(self):
        llm_rc = RequestClassification(
            is_specific_real_building=True,
            building_name_normalized="Sagrada Família, Barcelona",
            confidence=0.95,
            reasoning_hint="named landmark",
            source="llm",
        )
        mock_response = MagicMock()
        mock_response.choices = [MagicMock(message=MagicMock(content='{"request_classification": {"is_specific_real_building": true, "building_name_normalized": "Sagrada Família, Barcelona", "confidence": 0.95, "reasoning_hint": "named landmark"}}'))]
        mock_client = MagicMock()
        mock_client.chat.completions.create.return_value = mock_response

        with patch("app.services.building_request_classifier.classify_building_request_with_llm", return_value=llm_rc):
            with patch("app.services.llm_client.get_client", return_value=mock_client):
                rc = classify_building_request("生成圣家族大教堂", req=MagicMock())
        self.assertTrue(rc.is_specific_real_building)
        self.assertEqual(rc.source, "llm")


if __name__ == "__main__":
    unittest.main()
