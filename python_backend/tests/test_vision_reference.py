"""PR-4: vision reference / image URL pipeline."""

from __future__ import annotations

import unittest
from unittest.mock import patch

from app.models.request import ReferenceInput
from app.services.building_research_agent import plan_search_queries, research_building_profile
from app.services.vision_analyzer import (
    VisualAnalysis,
    analyze_references,
    merge_visual_into_profile,
)
from app.services.building_research_agent import synthesize_profile_rule_based


class ReferenceInputTest(unittest.TestCase):
    def test_image_url_normalization(self):
        ref = ReferenceInput(type="image_url", content="https://example.com/a.jpg")
        self.assertEqual(ref.normalized_image_url(), "https://example.com/a.jpg")

    def test_base64_normalization(self):
        ref = ReferenceInput(type="image_base64", content="abc123")
        self.assertTrue(ref.normalized_image_url().startswith("data:image/jpeg;base64,"))

    def test_web_image_url(self):
        ref = ReferenceInput(type="web_url", content="https://example.com/photo.png")
        self.assertEqual(ref.normalized_image_url(), "https://example.com/photo.png")


class VisionMergeTest(unittest.TestCase):
    def test_merge_visual_into_profile(self):
        profile = synthesize_profile_rule_based("unknown", "照着这个建", [])
        visual = VisualAnalysis(
            building_name="苏州博物馆",
            style="现代中式",
            distinctive_elements=["white walls", "water courtyard"],
            typical_width_blocks=40,
            confidence=0.8,
            notes="vision notes",
        )
        merged = merge_visual_into_profile(profile, visual)
        self.assertEqual(merged.identity.name, "苏州博物馆")
        self.assertEqual(merged.identity.style, "现代中式")
        self.assertGreaterEqual(merged.identity.confidence, 0.8)
        self.assertIn("white walls", merged.structure.distinctive_elements)
        self.assertEqual(merged.scale_hints.typical_width_blocks, 40)
        self.assertIn("[Visual]", merged.research_notes or "")

    def test_analyze_references_rule_fallback(self):
        refs = [
            ReferenceInput(
                type="web_url",
                content="https://example.com/article",
                caption="glass dome stadium",
            ),
        ]
        with patch(
            "app.services.vision_analyzer._fetch_web_page_snippet",
            return_value="",
        ):
            visual = analyze_references(refs, "build this", req=None)
        self.assertIsNotNone(visual)
        assert visual is not None
        self.assertIn("glass", " ".join(visual.distinctive_elements).lower())


class ResearchWithReferencesTest(unittest.TestCase):
    def test_plan_search_with_references_without_build_verb(self):
        should, queries, _ = plan_search_queries("照着这个", has_references=True)
        self.assertTrue(should)
        self.assertGreaterEqual(len(queries), 1)

    def test_research_profile_with_refs_mock_vision(self):
        refs = [
            ReferenceInput(
                type="image_url",
                content="https://example.com/building.jpg",
                caption="curved roof",
            ),
        ]

        def fake_search(q: str, n: int):
            return [{"title": "Ref", "snippet": "tower glass facade", "url": ""}]

        visual = VisualAnalysis(
            building_name="Test Tower",
            distinctive_elements=["tower", "glass"],
            confidence=0.7,
        )
        with patch(
            "app.services.vision_analyzer.analyze_references",
            return_value=visual,
        ):
            profile = research_building_profile(
                "照着这个建",
                references=refs,
                search_fn=fake_search,
            )
        self.assertIsNotNone(profile)
        assert profile is not None
        self.assertEqual(profile.identity.name, "Test Tower")


class RequestAdapterReferenceTest(unittest.TestCase):
    def test_forma_adapter_passes_references(self):
        from app.models.request_adapter import FormaRequestAdapter

        adapter = FormaRequestAdapter(
            requestText="build https://example.com/a.jpg",
            playerPos={"x": 0, "y": 64, "z": 0},
            references=[
                ReferenceInput(type="image_url", content="https://example.com/a.jpg"),
            ],
        )
        req = adapter.to_build_request()
        self.assertIsNotNone(req.references)
        assert req.references is not None
        self.assertEqual(len(req.references), 1)
        self.assertEqual(req.references[0].type, "image_url")


if __name__ == "__main__":
    unittest.main()
