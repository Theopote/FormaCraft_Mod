"""P1: unified prompt routing authority — research override wins over Java landmark leakage."""

from __future__ import annotations

import unittest

from app.models.building_profile import BuildingProfile, ProfileMinecraftStrategy
from app.services.building_plan_stage import (
    RESEARCH_OVERRIDE_MARKER,
    apply_research_landmark_override,
    strip_java_landmark_routing_blocks,
)
from app.services.building_research_agent import finalize_profile_minecraft_strategy


JAVA_LANDMARK_BLOCK = """
=== LANDMARK MODULE ROUTING (MANDATORY) ===
You MUST output MODULE with landmark:gothic_cathedral.
{"component_type": "MODULE", "features": ["landmark:gothic_cathedral"]}
"""

AVAILABLE_MODULES_BLOCK = """
AVAILABLE LANDMARK MODULES:
- gothic_cathedral
- temple_of_heaven
"""


class PromptRoutingAuthorityTest(unittest.TestCase):
    def test_chinese_stair_request_includes_executable_flight_and_plate_contract(self):
        from types import SimpleNamespace
        from unittest.mock import patch
        from app.services.ai_planner import _llm_plan_context_block

        req = SimpleNamespace(userMessage="两层住宅，宽3格直楼梯连接二楼", requestText="", chatHistory=[])
        with patch("app.services.ai_planner._LLMPLAN_INJECT_CULTURE_RAG", False):
            context = _llm_plan_context_block(req)
        self.assertIn("from:{x,y,z},to:{x,y,z}", context)
        self.assertIn("dimensions.height=1", context)
        self.assertIn("Text-only features do not generate stairs or holes", context)

    def test_strip_java_landmark_routing_blocks(self):
        system = (
            "=== SYSTEM RULES ===\n"
            "Follow JSON schema.\n\n"
            + JAVA_LANDMARK_BLOCK
            + "\n=== OTHER ===\n"
            "More rules."
        )
        out = strip_java_landmark_routing_blocks(system)
        self.assertNotIn("LANDMARK MODULE ROUTING", out)
        self.assertIn("=== SYSTEM RULES ===", out)
        self.assertIn("More rules.", out)

    def test_override_appended_after_java_landmark_block(self):
        profile = finalize_profile_minecraft_strategy(
            BuildingProfile(
                query="盖一座天坛",
                minecraft_strategy=ProfileMinecraftStrategy(),
            ),
            "盖一座天坛",
        )
        system = "=== BASE ===\n" + JAVA_LANDMARK_BLOCK + AVAILABLE_MODULES_BLOCK
        out = apply_research_landmark_override(system, profile)
        self.assertNotIn("LANDMARK MODULE ROUTING", out)
        self.assertNotIn("AVAILABLE LANDMARK MODULES", out)
        self.assertIn(RESEARCH_OVERRIDE_MARKER, out)
        self.assertLess(out.index("=== BASE ==="), out.index(RESEARCH_OVERRIDE_MARKER))

    def test_context_block_skips_landmark_routing_when_profile_present(self):
        from app.services.ai_planner import _llm_plan_context_block
        from app.services.building_research_agent import finalize_profile_minecraft_strategy

        class _Req:
            requestText = "生成旧金山金门大桥"
            userMessage = "生成旧金山金门大桥"
            chatHistory = []

        profile = finalize_profile_minecraft_strategy(
            BuildingProfile(
                query="生成旧金山金门大桥",
                minecraft_strategy=ProfileMinecraftStrategy(),
            ),
            "生成旧金山金门大桥",
        )
        with_profile = _llm_plan_context_block(_Req(), profile)
        without_profile = _llm_plan_context_block(_Req(), None)
        self.assertNotIn("LandmarkModuleRouting(JSON)", with_profile)
        self.assertIn("ResearchRouting(JSON)", with_profile)
        self.assertIn("suspension_bridge", with_profile)
        self.assertIn("LandmarkModuleRouting(JSON)", without_profile)


if __name__ == "__main__":
    unittest.main()
