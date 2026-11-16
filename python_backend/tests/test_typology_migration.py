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
        self.assertIn("radial_terrace_hall", ids)
        famen = get_typology("dense_eaves_pagoda")
        self.assertIsNotNone(famen)
        assert famen is not None
        self.assertEqual(famen.interpreter_id, "dense_eaves_pagoda")
        self.assertIsNone(famen.legacy_interpreter_id)
        self.assertEqual(famen.skeleton_type, "VERTICAL_STACK")

    def test_migration_map_famen(self):
        from app.services.typology_registry import get_migration, typology_for_legacy_module

        self.assertEqual(typology_for_legacy_module("famen_pagoda"), "dense_eaves_pagoda")
        self.assertEqual(typology_for_legacy_module("giant_wild_goose_pagoda"), "dense_eaves_pagoda")
        self.assertEqual(typology_for_legacy_module("temple_of_heaven"), "radial_terrace_hall")
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

    def test_generic_pagoda_finalize_does_not_auto_bind_typology_executor(self):
        from app.models.building_profile import BuildingProfile
        from app.services.building_research_agent import (
            finalize_profile_minecraft_strategy,
            resolve_explicit_structural_typology,
            resolve_structural_typology_for_intent,
        )

        # Keyword research may still resolve a typology id as evidence…
        self.assertEqual(
            resolve_structural_typology_for_intent(self.GENERIC_PROMPT),
            "dense_eaves_pagoda",
        )
        # …but open-world finalize must not bind it as a whole-building executor.
        self.assertIsNone(resolve_explicit_structural_typology(self.GENERIC_PROMPT))
        profile = finalize_profile_minecraft_strategy(BuildingProfile(), self.GENERIC_PROMPT)
        self.assertIsNone(profile.minecraft_strategy.structural_typology)
        self.assertIsNone(profile.minecraft_strategy.landmark_module)

    def test_explicit_typology_request_still_binds(self):
        from app.models.building_profile import BuildingProfile
        from app.services.building_research_agent import finalize_profile_minecraft_strategy

        plain = finalize_profile_minecraft_strategy(
            BuildingProfile(),
            "建造一座塔 typology_id=dense_eaves_pagoda",
        )
        self.assertFalse(plain.minecraft_strategy.model_designed_landmark)
        self.assertEqual(plain.minecraft_strategy.structural_typology, "dense_eaves_pagoda")
        self.assertEqual(plain.minecraft_strategy.recommended_components, ["STRUCTURE"])

    def test_negated_typology_id_does_not_bind_executor(self):
        from app.models.building_profile import BuildingProfile
        from app.services.building_research_agent import (
            finalize_profile_minecraft_strategy,
            resolve_explicit_structural_typology,
        )

        text = (
            "建造一座七层佛塔，不要使用 typology_id=dense_eaves_pagoda，"
            "让 AI 自己选择建筑结构。"
        )
        self.assertIsNone(resolve_explicit_structural_typology(text))
        profile = finalize_profile_minecraft_strategy(BuildingProfile(), text)
        self.assertIsNone(profile.minecraft_strategy.structural_typology)
        self.assertNotEqual(profile.minecraft_strategy.recommended_components, ["STRUCTURE"])

    def test_negated_typology_a_then_explicit_b_binds_b(self):
        from app.models.building_profile import BuildingProfile
        from app.services.building_research_agent import (
            finalize_profile_minecraft_strategy,
            resolve_explicit_structural_typology,
        )

        text = (
            "建造一座塔，不要使用 typology_id=dense_eaves_pagoda，"
            "改用 typology_id=tibetan_stupa"
        )
        self.assertEqual(resolve_explicit_structural_typology(text), "tibetan_stupa")
        profile = finalize_profile_minecraft_strategy(BuildingProfile(), text)
        self.assertEqual(profile.minecraft_strategy.structural_typology, "tibetan_stupa")
        self.assertEqual(profile.minecraft_strategy.recommended_components, ["STRUCTURE"])

    def test_famen_archetype_research_only(self):
        from app.services.archetype_detector import detect_archetype_local, PRESET_IDENTITY_MIN_CONFIDENCE
        from app.services.archetype_registry import get_archetype_def
        from app.services.building_research_agent import resolve_landmark_module_for_intent

        match = detect_archetype_local(self.FAMEN_PROMPT)
        self.assertIsNotNone(match)
        assert match is not None
        self.assertEqual(match.id, "famen_pagoda")
        self.assertGreaterEqual(match.confidence, PRESET_IDENTITY_MIN_CONFIDENCE)
        defn = get_archetype_def("famen_pagoda")
        self.assertIsNotNone(defn)
        assert defn is not None
        self.assertTrue(defn.research_only)
        self.assertEqual(defn.typology_id, "dense_eaves_pagoda")
        self.assertFalse(match.matches_registered_preset_identity())
        self.assertIsNone(resolve_landmark_module_for_intent(self.FAMEN_PROMPT))

    def test_famen_profile_typology_strategy(self):
        from app.services.building_research_agent import finalize_profile_minecraft_strategy
        from app.models.building_profile import BuildingProfile

        profile = finalize_profile_minecraft_strategy(BuildingProfile(), self.FAMEN_PROMPT)
        mc = profile.minecraft_strategy
        self.assertTrue(mc.model_designed_landmark)
        self.assertIsNone(mc.structural_typology)
        self.assertIsNone(mc.reference_landmark)
        self.assertIsNone(mc.landmark_module)

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
        self.assertTrue(mc.model_designed_landmark)
        self.assertIsNone(mc.structural_typology)
        self.assertIsNone(mc.reference_landmark)
        self.assertIsNone(mc.landmark_module)

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
        from app.services.archetype_detector import detect_archetype_local, PRESET_IDENTITY_MIN_CONFIDENCE
        from app.services.archetype_registry import get_archetype_def
        from app.services.building_research_agent import resolve_landmark_module_for_intent

        match = detect_archetype_local(self.DAYANTA_PROMPT)
        self.assertIsNotNone(match)
        assert match is not None
        self.assertEqual(match.id, "giant_wild_goose_pagoda")
        self.assertGreaterEqual(match.confidence, PRESET_IDENTITY_MIN_CONFIDENCE)
        defn = get_archetype_def("giant_wild_goose_pagoda")
        self.assertIsNotNone(defn)
        assert defn is not None
        self.assertTrue(defn.research_only)
        self.assertEqual(defn.typology_id, "dense_eaves_pagoda")
        self.assertFalse(match.matches_registered_preset_identity())
        self.assertIsNone(resolve_landmark_module_for_intent(self.DAYANTA_PROMPT))

    def test_dayanta_profile_typology_strategy(self):
        from app.services.building_research_agent import finalize_profile_minecraft_strategy
        from app.models.building_profile import BuildingProfile

        profile = finalize_profile_minecraft_strategy(BuildingProfile(), self.DAYANTA_PROMPT)
        mc = profile.minecraft_strategy
        self.assertTrue(mc.model_designed_landmark)
        self.assertIsNone(mc.structural_typology)
        self.assertIsNone(mc.reference_landmark)
        self.assertIsNone(mc.landmark_module)

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


class TempleTypologyMigrationTest(unittest.TestCase):
    TEMPLE_PROMPT = "盖一座天坛"

    def test_temple_culture_typology_id(self):
        from app.services.keyword_culture_retriever import retrieve

        rag = retrieve(self.TEMPLE_PROMPT, topK=3, fewShotK=0)
        self.assertEqual(rag["hits"][0]["id"], "temple_of_heaven")
        self.assertEqual(rag.get("structuralTypologyId"), "radial_terrace_hall")
        self.assertIsNone(rag.get("landmarkModuleId"))

    def test_temple_typology_retriever(self):
        from app.services.typology_retriever import resolve_typology_for_intent

        match = resolve_typology_for_intent(self.TEMPLE_PROMPT, culture_card_id="temple_of_heaven")
        self.assertIsNotNone(match)
        assert match is not None
        self.assertEqual(match.typology_id, "radial_terrace_hall")
        self.assertEqual(match.reference_landmark_id, "temple_of_heaven")

    def test_temple_archetype_research_only(self):
        from app.services.archetype_detector import detect_archetype_local, PRESET_IDENTITY_MIN_CONFIDENCE
        from app.services.archetype_registry import get_archetype_def
        from app.services.building_research_agent import resolve_landmark_module_for_intent

        match = detect_archetype_local(self.TEMPLE_PROMPT)
        self.assertIsNotNone(match)
        assert match is not None
        self.assertEqual(match.id, "temple_of_heaven")
        self.assertGreaterEqual(match.confidence, PRESET_IDENTITY_MIN_CONFIDENCE)
        defn = get_archetype_def("temple_of_heaven")
        self.assertTrue(defn.research_only)
        self.assertEqual(defn.typology_id, "radial_terrace_hall")
        self.assertFalse(match.matches_registered_preset_identity())
        self.assertIsNone(resolve_landmark_module_for_intent(self.TEMPLE_PROMPT))

    def test_temple_profile_typology_strategy(self):
        from app.services.building_research_agent import finalize_profile_minecraft_strategy
        from app.models.building_profile import BuildingProfile

        profile = finalize_profile_minecraft_strategy(BuildingProfile(), self.TEMPLE_PROMPT)
        mc = profile.minecraft_strategy
        self.assertTrue(mc.model_designed_landmark)
        self.assertIsNone(mc.structural_typology)
        self.assertIsNone(mc.reference_landmark)
        self.assertIsNone(mc.landmark_module)

    def test_temple_proportion_typology_field(self):
        from app.services.proportion_retriever import retrieve_proportion_card

        card = retrieve_proportion_card(self.TEMPLE_PROMPT)
        assert card is not None
        self.assertEqual(card.get("id"), "temple_of_heaven")
        self.assertEqual(card.get("typology"), "radial_terrace_hall")

    def test_temple_beats_pagoda(self):
        from app.services.keyword_culture_retriever import retrieve

        rag = retrieve(self.TEMPLE_PROMPT, topK=3, fewShotK=0)
        ids = [h["id"] for h in rag.get("hits", [])]
        self.assertEqual(ids[0], "temple_of_heaven")
        self.assertNotIn("famen_pagoda", ids[:1])


if __name__ == "__main__":
    unittest.main()
