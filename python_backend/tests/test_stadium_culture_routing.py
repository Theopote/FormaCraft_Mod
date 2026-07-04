"""Tests for landmark routing tiers and creative intent."""

from __future__ import annotations

import unittest


class LandmarkRoutingPolicyTest(unittest.TestCase):
    PROMPT = "在锚点位置生成现代风格的椭圆形体育场建筑"
    EXPLICIT = "在锚点位置生成鸟巢体育馆"
    CREATIVE = "在锚点位置原创设计一座独特的现代椭圆体育场，不要地标"

    def test_typological_stadium_is_suggested(self):
        from app.services.landmark_routing_policy import RoutingTier, resolve_for_user_intent

        decision = resolve_for_user_intent(self.PROMPT)
        self.assertIsNotNone(decision)
        assert decision is not None
        self.assertEqual(decision.module_id, "birds_nest_stadium")
        self.assertEqual(decision.tier, RoutingTier.SUGGESTED)

    def test_explicit_birds_nest_is_mandatory(self):
        from app.services.landmark_routing_policy import RoutingTier, resolve_for_user_intent

        decision = resolve_for_user_intent(self.EXPLICIT)
        self.assertIsNotNone(decision)
        assert decision is not None
        self.assertEqual(decision.tier, RoutingTier.MANDATORY)

    def test_creative_intent_blocks_routing(self):
        from app.services.landmark_routing_policy import is_creative_or_original_intent, resolve_for_user_intent
        from app.services.keyword_culture_retriever import resolve_landmark_module_routing

        self.assertTrue(is_creative_or_original_intent(self.CREATIVE))
        self.assertIsNone(resolve_for_user_intent(self.CREATIVE))
        self.assertIsNone(resolve_landmark_module_routing(self.CREATIVE))


class StadiumCultureRoutingTest(unittest.TestCase):
    PROMPT = "在锚点位置生成现代风格的椭圆形体育场建筑"

    def test_culture_card_matches_stadium_prompt(self):
        from app.services.keyword_culture_retriever import retrieve

        rag = retrieve(self.PROMPT, topK=2, fewShotK=1)
        self.assertTrue(rag.get("hits"), "expected culture card hit")
        best = rag["hits"][0]
        self.assertEqual(best.get("id"), "modern_stadium_elliptical")
        self.assertEqual(rag.get("landmarkModuleId"), "birds_nest_stadium")
        self.assertTrue(rag.get("llmPlanFewShots"), "expected LlmPlan few-shot example")

    def test_resolve_landmark_module_routing_suggested_tier(self):
        from app.services.keyword_culture_retriever import resolve_landmark_module_routing

        routing = resolve_landmark_module_routing(self.PROMPT)
        self.assertIsNotNone(routing)
        assert routing is not None
        self.assertEqual(routing.get("moduleId"), "birds_nest_stadium")
        self.assertEqual(routing.get("routingTier"), "suggested")
        self.assertEqual(routing.get("feature"), "landmark:birds_nest_stadium")
        self.assertIn("exampleParams", routing)
        self.assertIn("llmPlanFewShots", routing)


if __name__ == "__main__":
    unittest.main()
