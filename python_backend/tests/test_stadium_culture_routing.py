"""Week 2: stadium culture card + landmark routing tests."""

from __future__ import annotations

import unittest


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

    def test_resolve_landmark_module_routing(self):
        from app.services.keyword_culture_retriever import resolve_landmark_module_routing

        routing = resolve_landmark_module_routing(self.PROMPT)
        self.assertIsNotNone(routing)
        assert routing is not None
        self.assertEqual(routing.get("moduleId"), "birds_nest_stadium")
        self.assertEqual(routing.get("feature"), "landmark:birds_nest_stadium")
        self.assertEqual(routing.get("componentType"), "MODULE")
        self.assertIn("llmPlanFewShots", routing)


if __name__ == "__main__":
    unittest.main()
