"""ReferenceBlueprint schema + Google CSE + LLM synth defaults."""

from __future__ import annotations

import json
import os
import unittest
from pathlib import Path
from unittest.mock import patch

from app.models.reference_blueprint import ReferenceBlueprint, parse_reference_blueprint
from app.models.request import ReferenceInput
from app.services.building_research_agent import (
    _building_research_llm_synth,
    synthesize_profile_rule_based,
)
from app.services.architecture_researcher import (
    google_cse_configured,
    search_architecture_reference,
)
from app.services.vision_analyzer import analyze_references, merge_visual_into_profile


FIXTURE = Path(__file__).parent / "fixtures" / "reference_blueprint_pagoda.json"


class ReferenceBlueprintParseTest(unittest.TestCase):
    def test_parse_pagoda_fixture(self):
        raw = json.loads(FIXTURE.read_text(encoding="utf-8"))
        bp = parse_reference_blueprint(raw)
        self.assertIsNotNone(bp)
        assert bp is not None
        self.assertEqual(bp.metadata.get("project_name"), "steampunk_oriental_pagoda")
        self.assertEqual(bp.style_tag(), "oriental_fantasy_steampunk")
        self.assertEqual(bp.dimensions().get("height_y"), 42)
        self.assertGreaterEqual(len(bp.distinctive_features()), 1)

    def test_reference_json_input_type(self):
        raw = FIXTURE.read_text(encoding="utf-8")
        ref = ReferenceInput(type="reference_json", content=raw)
        self.assertTrue(ref.is_reference_json())
        visual = analyze_references([ref], "照着这个建", req=None)
        self.assertIsNotNone(visual)
        assert visual is not None
        self.assertIsNotNone(visual.reference_blueprint)
        self.assertEqual(visual.typical_width_blocks, 32)
        self.assertEqual(visual.typical_height_blocks, 42)

    def test_merge_blueprint_into_profile(self):
        bp = parse_reference_blueprint(json.loads(FIXTURE.read_text(encoding="utf-8")))
        assert bp is not None
        from app.services.vision_analyzer import _visual_from_blueprint

        visual = _visual_from_blueprint(bp)
        profile = synthesize_profile_rule_based("unknown", "build pagoda", [])
        merged = merge_visual_into_profile(profile, visual)
        self.assertIsNotNone(merged.reference_blueprint)
        self.assertEqual(merged.identity.style, "oriental_fantasy_steampunk")
        self.assertEqual(merged.scale_hints.typical_height_blocks, 42)
        self.assertIsNone(merged.minecraft_strategy.landmark_module)


class LlmSynthDefaultTest(unittest.TestCase):
    @patch.dict(os.environ, {}, clear=False)
    def test_llm_synth_on_by_default(self):
        os.environ.pop("BUILDING_RESEARCH_LLM_SYNTH", None)
        self.assertTrue(_building_research_llm_synth())

    @patch.dict(os.environ, {"BUILDING_RESEARCH_LLM_SYNTH": "off"}, clear=False)
    def test_llm_synth_can_disable(self):
        self.assertFalse(_building_research_llm_synth())


class GoogleCseTest(unittest.TestCase):
    @patch.dict(os.environ, {}, clear=False)
    def test_not_configured_without_keys(self):
        os.environ.pop("GOOGLE_CSE_API_KEY", None)
        os.environ.pop("GOOGLE_CSE_CX", None)
        self.assertFalse(google_cse_configured())

    @patch.dict(
        os.environ,
        {"GOOGLE_CSE_API_KEY": "test-key", "GOOGLE_CSE_CX": "test-cx"},
        clear=False,
    )
    @patch("app.services.architecture_researcher.requests.get")
    def test_google_cse_used_in_search_chain(self, mock_get):
        mock_get.return_value.status_code = 200
        mock_get.return_value.json.return_value = {
            "items": [
                {
                    "title": "Xi'an Olympic Center architecture",
                    "snippet": "stadium architecture design 60000 seats",
                    "link": "https://example.com/arch",
                }
            ]
        }
        with patch(
            "app.services.architecture_researcher._search_wikipedia",
            return_value=[],
        ):
            results = search_architecture_reference("西安奥体中心 建筑", max_results=2)
        self.assertGreaterEqual(len(results), 1)
        self.assertIn("architecture", results[0]["snippet"].lower())


if __name__ == "__main__":
    unittest.main()
