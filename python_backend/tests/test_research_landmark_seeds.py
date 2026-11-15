"""Research-only landmark seeds — non-binding curated references."""

from __future__ import annotations

import unittest

from app.models.building_profile import BuildingProfile
from app.services.building_research_agent import (
    finalize_profile_minecraft_strategy,
    resolve_landmark_module_for_intent,
)
from app.services.research_landmark_seeds import (
    RESEARCH_LANDMARK_SEEDS,
    apply_research_landmark_seed,
    resolve_research_landmark_id,
)


class ResolveResearchLandmarkTest(unittest.TestCase):
    def test_sagrada_resolves(self):
        self.assertEqual(resolve_research_landmark_id("生成圣家族大教堂"), "sagrada_familia")

    def test_sydney_opera_resolves(self):
        self.assertEqual(resolve_research_landmark_id("recreate Sydney Opera House"), "sydney_opera_house")

    def test_louvre_resolves(self):
        self.assertEqual(resolve_research_landmark_id("建造卢浮宫"), "louvre_museum")

    def test_gothic_template_not_research_only(self):
        self.assertIsNone(resolve_research_landmark_id("哥特大教堂"))

    def test_generic_cathedral_not_resolved(self):
        self.assertIsNone(resolve_research_landmark_id("一个大教堂"))


class ApplyResearchLandmarkSeedTest(unittest.TestCase):
    def test_sagrada_records_non_binding_note_only(self):
        base = BuildingProfile(query="圣家族大教堂")
        updated, lid = apply_research_landmark_seed(base, "生成圣家族大教堂")
        self.assertEqual(lid, "sagrada_familia")
        self.assertIn("[LandmarkSeed:sagrada_familia]", updated.research_notes or "")
        self.assertIn("non-binding", (updated.research_notes or "").lower())
        self.assertEqual(updated.structure.distinguishing_features, [])
        self.assertIsNone(updated.identity.architect)
        self.assertIsNone(updated.scale_hints.typical_width_blocks)
        self.assertIsNone(updated.minecraft_strategy.landmark_module)
        self.assertEqual(updated.minecraft_strategy.recommended_components, ["MASS_MAIN", "ROOF", "FACADE_WINDOWS", "ENTRANCE"])

    def test_sydney_opera_does_not_inject_shell_features(self):
        base = BuildingProfile()
        updated, lid = apply_research_landmark_seed(base, "悉尼歌剧院")
        self.assertEqual(lid, "sydney_opera_house")
        self.assertEqual(updated.structure.distinguishing_features, [])
        self.assertIn("non-binding", (updated.research_notes or "").lower())

    def test_seed_does_not_mutate_existing_features(self):
        base = BuildingProfile(
            structure={
                "distinguishing_features": ["hyperboloid parabolic bell towers"],
            }
        )
        updated, _ = apply_research_landmark_seed(base, "圣家族大教堂")
        self.assertEqual(
            updated.structure.distinguishing_features,
            ["hyperboloid parabolic bell towers"],
        )

    def test_all_json_research_landmarks_have_seeds(self):
        """Every researchOnly archetype in seeds file should have curated features."""
        expected = {
            "sagrada_familia",
            "notre_dame_paris",
            "cologne_cathedral",
            "chartres_cathedral",
            "sydney_opera_house",
            "louvre_museum",
            "suzhou_museum",
            "forbidden_city",
            "taj_mahal",
            "white_house",
            "guggenheim_bilbao",
            "burj_khalifa",
            "disney_castle",
            "fushimi_inari_shrine",
            "himeji_castle",
            "wuzhen_water_town",
            "zhouzhuang_water_town",
        }
        self.assertEqual(set(RESEARCH_LANDMARK_SEEDS.keys()), expected)


class FinalizeWithSeedIntegrationTest(unittest.TestCase):
    def test_sagrada_finalize_no_module_and_no_seed_features(self):
        profile = BuildingProfile(query="圣家族大教堂")
        final = finalize_profile_minecraft_strategy(profile, "生成圣家族大教堂")
        self.assertIsNone(resolve_landmark_module_for_intent("生成圣家族大教堂"))
        self.assertIsNone(final.minecraft_strategy.landmark_module)
        self.assertIsNone(final.minecraft_strategy.structural_typology)
        self.assertIn("[LandmarkSeed:sagrada_familia]", final.research_notes or "")
        # Seeds must not become design commands.
        self.assertFalse(
            any("hyperboloid" in f.lower() for f in final.structure.distinguishing_features)
        )

    def test_louvre_finalize_keeps_open_composition(self):
        profile = BuildingProfile(query="卢浮宫")
        final = finalize_profile_minecraft_strategy(profile, "建造卢浮宫")
        self.assertIsNone(final.minecraft_strategy.landmark_module)
        self.assertTrue(final.minecraft_strategy.model_designed_landmark)
        self.assertIn("non-binding", (final.research_notes or "").lower())


if __name__ == "__main__":
    unittest.main()
