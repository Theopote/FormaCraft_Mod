"""Typology-first migration tests — Famen/Foguang mapping."""

from __future__ import annotations

import unittest


class TypologyRegistryTest(unittest.TestCase):
    def test_registry_loads_two_typologies(self):
        from app.services.typology_registry import get_typology, list_typologies

        defs = list_typologies()
        ids = {d.id for d in defs}
        self.assertIn("dense_eaves_pagoda", ids)
        self.assertIn("tailiang_timber_hall", ids)
        famen = get_typology("dense_eaves_pagoda")
        self.assertIsNotNone(famen)
        assert famen is not None
        self.assertEqual(famen.legacy_interpreter_id, "famen_pagoda")
        self.assertEqual(famen.skeleton_type, "VERTICAL_STACK")

    def test_migration_map_famen(self):
        from app.services.typology_registry import get_migration, typology_for_legacy_module

        self.assertEqual(typology_for_legacy_module("famen_pagoda"), "dense_eaves_pagoda")
        self.assertEqual(typology_for_legacy_module("giant_wild_goose_pagoda"), "dense_eaves_pagoda")
        entry = get_migration("foguang_temple_hall")
        self.assertIsNotNone(entry)
        assert entry is not None
        self.assertEqual(entry.typology_id, "tailiang_timber_hall")
        self.assertEqual(entry.phase, "typology_first")


class FamenTypologyMigrationTest(unittest.TestCase):
    FAMEN_PROMPT = "在锚点位置生成法门寺舍利塔"
    GENERIC_PROMPT = "生成一座唐代密檐砖塔"

    def test_famen_culture_typology_id(self):
        from app.services.keyword_culture_retriever import retrieve

        rag = retrieve(self.FAMEN_PROMPT, topK=3, fewShotK=0)
        self.assertEqual(rag["hits"][0]["id"], "famen_pagoda")
        self.assertEqual(rag.get("structuralTypologyId"), "dense_eaves_pagoda")
        self.assertIsNone(rag.get("landmarkModuleId"))

    def test_famen_typology_retriever(self):
        from app.services.typology_retriever import resolve_typology_for_intent

        match = resolve_typology_for_intent(self.FAMEN_PROMPT, culture_card_id="famen_pagoda")
        self.assertIsNotNone(match)
        assert match is not None
        self.assertEqual(match.typology_id, "dense_eaves_pagoda")
        self.assertEqual(match.reference_landmark_id, "famen_pagoda")

    def test_generic_dense_eaves_keyword(self):
        from app.services.typology_retriever import resolve_typology_for_intent

        match = resolve_typology_for_intent(self.GENERIC_PROMPT)
        self.assertIsNotNone(match)
        assert match is not None
        self.assertEqual(match.typology_id, "dense_eaves_pagoda")

    def test_famen_archetype_research_only(self):
        from app.services.archetype_detector import detect_archetype_local, MODULE_ROUTE_MIN_CONFIDENCE
        from app.services.archetype_registry import get_archetype_def
        from app.services.building_research_agent import resolve_landmark_module_for_intent

        match = detect_archetype_local(self.FAMEN_PROMPT)
        self.assertIsNotNone(match)
        assert match is not None
        self.assertEqual(match.id, "famen_pagoda")
        self.assertGreaterEqual(match.confidence, MODULE_ROUTE_MIN_CONFIDENCE)
        defn = get_archetype_def("famen_pagoda")
        self.assertIsNotNone(defn)
        assert defn is not None
        self.assertTrue(defn.research_only)
        self.assertEqual(defn.typology_id, "dense_eaves_pagoda")
        self.assertFalse(match.qualifies_for_module_route())
        self.assertIsNone(resolve_landmark_module_for_intent(self.FAMEN_PROMPT))

    def test_famen_profile_typology_strategy(self):
        from app.services.building_research_agent import finalize_profile_minecraft_strategy
        from app.models.building_profile import BuildingProfile

        profile = finalize_profile_minecraft_strategy(BuildingProfile(), self.FAMEN_PROMPT)
        mc = profile.minecraft_strategy
        self.assertEqual(mc.structural_typology, "dense_eaves_pagoda")
        self.assertEqual(mc.reference_landmark, "famen_pagoda")
        self.assertIsNone(mc.landmark_module)
        self.assertEqual(mc.skeleton_type, "VERTICAL_STACK")

    def test_famen_proportion_typology_field(self):
        from app.services.proportion_retriever import retrieve_proportion_card

        card = retrieve_proportion_card(self.FAMEN_PROMPT)
        assert card is not None
        self.assertEqual(card.get("typology"), "dense_eaves_pagoda")


class FoguangTypologyMigrationTest(unittest.TestCase):
    FOGUANG_PROMPT = "在锚点位置生成佛光寺大殿"

    def test_foguang_culture_typology_id(self):
        from app.services.keyword_culture_retriever import retrieve

        rag = retrieve(self.FOGUANG_PROMPT, topK=3, fewShotK=0)
        self.assertEqual(rag["hits"][0]["id"], "foguang_temple_hall")
        self.assertEqual(rag.get("structuralTypologyId"), "tailiang_timber_hall")
        self.assertIsNone(rag.get("landmarkModuleId"))

    def test_foguang_typology_retriever(self):
        from app.services.typology_retriever import resolve_typology_for_intent

        match = resolve_typology_for_intent(self.FOGUANG_PROMPT, culture_card_id="foguang_temple_hall")
        self.assertIsNotNone(match)
        assert match is not None
        self.assertEqual(match.typology_id, "tailiang_timber_hall")
        self.assertEqual(match.reference_landmark_id, "foguang_temple_hall")

    def test_foguang_profile_typology_strategy(self):
        from app.services.building_research_agent import finalize_profile_minecraft_strategy
        from app.models.building_profile import BuildingProfile

        profile = finalize_profile_minecraft_strategy(BuildingProfile(), self.FOGUANG_PROMPT)
        mc = profile.minecraft_strategy
        self.assertEqual(mc.structural_typology, "tailiang_timber_hall")
        self.assertEqual(mc.reference_landmark, "foguang_temple_hall")
        self.assertIsNone(mc.landmark_module)
        self.assertEqual(mc.skeleton_type, "GRID_BAY")

    def test_foguang_proportion_typology_field(self):
        from app.services.proportion_retriever import retrieve_proportion_card

        card = retrieve_proportion_card(self.FOGUANG_PROMPT)
        assert card is not None
        self.assertEqual(card.get("typology"), "tailiang_timber_hall")


class DayantaTypologyMigrationTest(unittest.TestCase):
    DAYANTA_PROMPT = "在锚点位置生成大雁塔"

    def test_dayanta_culture_typology_id(self):
        from app.services.keyword_culture_retriever import retrieve

        rag = retrieve(self.DAYANTA_PROMPT, topK=3, fewShotK=0)
        self.assertEqual(rag["hits"][0]["id"], "giant_wild_goose_pagoda")
        self.assertEqual(rag.get("structuralTypologyId"), "dense_eaves_pagoda")
        self.assertIsNone(rag.get("landmarkModuleId"))

    def test_dayanta_typology_retriever(self):
        from app.services.typology_retriever import resolve_typology_for_intent

        match = resolve_typology_for_intent(
            self.DAYANTA_PROMPT, culture_card_id="giant_wild_goose_pagoda"
        )
        self.assertIsNotNone(match)
        assert match is not None
        self.assertEqual(match.typology_id, "dense_eaves_pagoda")
        self.assertEqual(match.reference_landmark_id, "giant_wild_goose_pagoda")

    def test_dayanta_archetype_typology(self):
        from app.services.typology_registry import typology_for_archetype

        self.assertEqual(typology_for_archetype("giant_wild_goose_pagoda"), "dense_eaves_pagoda")

    def test_dayanta_archetype_research_only(self):
        from app.services.archetype_detector import detect_archetype_local, MODULE_ROUTE_MIN_CONFIDENCE
        from app.services.archetype_registry import get_archetype_def
        from app.services.building_research_agent import resolve_landmark_module_for_intent

        match = detect_archetype_local(self.DAYANTA_PROMPT)
        self.assertIsNotNone(match)
        assert match is not None
        self.assertEqual(match.id, "giant_wild_goose_pagoda")
        self.assertGreaterEqual(match.confidence, MODULE_ROUTE_MIN_CONFIDENCE)
        defn = get_archetype_def("giant_wild_goose_pagoda")
        self.assertIsNotNone(defn)
        assert defn is not None
        self.assertTrue(defn.research_only)
        self.assertEqual(defn.typology_id, "dense_eaves_pagoda")
        self.assertFalse(match.qualifies_for_module_route())
        self.assertIsNone(resolve_landmark_module_for_intent(self.DAYANTA_PROMPT))

    def test_dayanta_profile_typology_strategy(self):
        from app.services.building_research_agent import finalize_profile_minecraft_strategy
        from app.models.building_profile import BuildingProfile

        profile = finalize_profile_minecraft_strategy(BuildingProfile(), self.DAYANTA_PROMPT)
        mc = profile.minecraft_strategy
        self.assertEqual(mc.structural_typology, "dense_eaves_pagoda")
        self.assertEqual(mc.reference_landmark, "giant_wild_goose_pagoda")
        self.assertIsNone(mc.landmark_module)
        self.assertEqual(mc.skeleton_type, "VERTICAL_STACK")

    def test_dayanta_proportion_typology_field(self):
        from app.services.proportion_retriever import retrieve_proportion_card

        card = retrieve_proportion_card(self.DAYANTA_PROMPT)
        assert card is not None
        self.assertEqual(card.get("id"), "giant_wild_goose_pagoda")
        self.assertEqual(card.get("typology"), "dense_eaves_pagoda")

    def test_famen_dayanta_no_cross_contamination(self):
        from app.services.keyword_culture_retriever import retrieve

        famen = retrieve("法门寺舍利塔", topK=3, fewShotK=0)
        dayanta = retrieve("大雁塔", topK=3, fewShotK=0)
        self.assertEqual(famen["hits"][0]["id"], "famen_pagoda")
        self.assertEqual(dayanta["hits"][0]["id"], "giant_wild_goose_pagoda")
        self.assertEqual(famen.get("structuralTypologyId"), "dense_eaves_pagoda")
        self.assertEqual(dayanta.get("structuralTypologyId"), "dense_eaves_pagoda")


if __name__ == "__main__":
    unittest.main()
