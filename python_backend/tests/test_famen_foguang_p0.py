"""P0: Famen pagoda + Foguang Temple Hall culture/proportion RAG."""

from __future__ import annotations

import unittest


class FamenPagodaCultureTest(unittest.TestCase):
    FAMEN_PROMPT = "在锚点位置生成法门寺舍利塔"

    def test_famen_culture_card(self):
        from app.services.keyword_culture_retriever import retrieve

        rag = retrieve(self.FAMEN_PROMPT, topK=3, fewShotK=0)
        self.assertTrue(rag.get("hits"))
        self.assertEqual(rag["hits"][0]["id"], "famen_pagoda")
        self.assertEqual(rag.get("proportionCardId"), "famen_pagoda")
        self.assertEqual(rag.get("landmarkModuleId"), "giant_wild_goose_pagoda")
        self.assertTrue(rag.get("llmPlanFewShots"))

    def test_famen_beats_temple_of_heaven(self):
        from app.services.keyword_culture_retriever import retrieve

        rag = retrieve(self.FAMEN_PROMPT, topK=3, fewShotK=0)
        ids = [h["id"] for h in rag.get("hits", [])]
        self.assertEqual(ids[0], "famen_pagoda")
        self.assertNotIn("temple_of_heaven", ids[:1])

    def test_famen_proportion_retriever(self):
        from app.services.proportion_retriever import retrieve_proportion_card

        card = retrieve_proportion_card(self.FAMEN_PROMPT)
        assert card is not None
        self.assertEqual(card.get("id"), "famen_pagoda")
        self.assertIn("floor_count", card.get("ratios", {}))

    def test_famen_archetype(self):
        from app.services.archetype_detector import detect_archetype_local, MODULE_ROUTE_MIN_CONFIDENCE

        match = detect_archetype_local(self.FAMEN_PROMPT)
        self.assertIsNotNone(match)
        self.assertEqual(match.id, "famen_pagoda")
        self.assertGreaterEqual(match.confidence, MODULE_ROUTE_MIN_CONFIDENCE)
        self.assertTrue(match.qualifies_for_module_route())


class FoguangTempleHallCultureTest(unittest.TestCase):
    FOGUANG_PROMPT = "在锚点位置生成佛光寺大殿"

    def test_foguang_culture_card(self):
        from app.services.keyword_culture_retriever import retrieve

        rag = retrieve(self.FOGUANG_PROMPT, topK=3, fewShotK=0)
        self.assertTrue(rag.get("hits"))
        self.assertEqual(rag["hits"][0]["id"], "foguang_temple_hall")
        self.assertEqual(rag.get("proportionCardId"), "foguang_temple_hall")
        self.assertIsNone(rag.get("landmarkModuleId"))
        self.assertTrue(rag.get("llmPlanFewShots"))

    def test_foguang_beats_cottage(self):
        from app.services.keyword_culture_retriever import retrieve

        rag = retrieve(self.FOGUANG_PROMPT, topK=3, fewShotK=0)
        ids = [h["id"] for h in rag.get("hits", [])]
        self.assertEqual(ids[0], "foguang_temple_hall")

    def test_foguang_proportion_retriever(self):
        from app.services.proportion_retriever import retrieve_proportion_card

        card = retrieve_proportion_card(self.FOGUANG_PROMPT)
        assert card is not None
        self.assertEqual(card.get("id"), "foguang_temple_hall")
        self.assertIn("bay_count_x", card.get("ratios", {}))

    def test_foguang_archetype_research_only(self):
        from app.services.archetype_detector import detect_archetype_local, MODULE_ROUTE_MIN_CONFIDENCE
        from app.services.archetype_registry import get_archetype_def

        match = detect_archetype_local(self.FOGUANG_PROMPT)
        self.assertIsNotNone(match)
        self.assertEqual(match.id, "foguang_temple_hall")
        self.assertGreaterEqual(match.confidence, MODULE_ROUTE_MIN_CONFIDENCE)
        defn = get_archetype_def("foguang_temple_hall")
        self.assertIsNotNone(defn)
        self.assertTrue(defn.research_only)
        self.assertFalse(match.qualifies_for_module_route())


if __name__ == "__main__":
    unittest.main()
