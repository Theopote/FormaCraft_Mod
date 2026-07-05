"""Research-only landmark seeds — distinguishing features injection."""

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
    def test_sagrada_injects_distinguishing_features(self):
        base = BuildingProfile(query="圣家族大教堂")
        updated, lid = apply_research_landmark_seed(base, "生成圣家族大教堂")
        self.assertEqual(lid, "sagrada_familia")
        feats = updated.structure.distinguishing_features
        self.assertTrue(any("hyperboloid" in f.lower() or "tree-like" in f.lower() for f in feats))
        self.assertEqual(updated.identity.architect, "Antoni Gaudí")
        self.assertIsNone(updated.minecraft_strategy.landmark_module)

    def test_sydney_opera_shell_features(self):
        base = BuildingProfile()
        updated, lid = apply_research_landmark_seed(base, "悉尼歌剧院")
        self.assertEqual(lid, "sydney_opera_house")
        joined = " ".join(updated.structure.distinguishing_features).lower()
        self.assertIn("shell", joined)

    def test_seed_does_not_duplicate_features(self):
        base = BuildingProfile(
            structure={
                "distinguishing_features": ["hyperboloid parabolic bell towers"],
            }
        )
        updated, _ = apply_research_landmark_seed(base, "圣家族大教堂")
        self.assertEqual(
            updated.structure.distinguishing_features.count("hyperboloid parabolic bell towers"),
            1,
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
    def test_sagrada_finalize_no_module_with_features(self):
        profile = BuildingProfile(query="圣家族大教堂")
        final = finalize_profile_minecraft_strategy(profile, "生成圣家族大教堂")
        self.assertIsNone(resolve_landmark_module_for_intent("生成圣家族大教堂"))
        self.assertIsNone(final.minecraft_strategy.landmark_module)
        self.assertTrue(final.structure.distinguishing_features)
        joined = " ".join(final.structure.distinguishing_features).lower()
        self.assertTrue("hyperboloid" in joined or "tree-like" in joined or "mosaic" in joined)

    def test_louvre_fidelity_mentions_research(self):
        profile = BuildingProfile(query="卢浮宫")
        final = finalize_profile_minecraft_strategy(profile, "建造卢浮宫")
        self.assertTrue(final.structure.distinguishing_features)
        joined = " ".join(final.structure.distinguishing_features).lower()
        self.assertTrue("pyramid" in joined or "glass" in joined)


if __name__ == "__main__":
    unittest.main()
