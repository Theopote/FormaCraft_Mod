"""Intent clarification — ask user when build request is too vague."""

from __future__ import annotations

import os
import unittest
from unittest.mock import patch

from app.models.request import BuildRequest, PlayerInfo, Vec3i, WorldContext
from app.services.intent_clarification import (
    assess_clarification_needs,
    build_effective_intent_text,
    is_follow_up_to_clarification,
    maybe_clarification_response,
)


def _build_req(
    user_message: str,
    *,
    chat_history: list[str] | None = None,
    prompt_mode: str = "BUILD",
    references: list | None = None,
) -> BuildRequest:
    return BuildRequest(
        player=PlayerInfo(name="test", pos=Vec3i(x=0, y=64, z=0), facing="NORTH"),
        world=WorldContext(dimension="minecraft:overworld"),
        requestText="USER REQUEST:\n" + user_message,
        userMessage=user_message,
        promptMode=prompt_mode,
        chatHistory=chat_history,
        references=references,
    )


class IntentClarificationTest(unittest.TestCase):
    def setUp(self):
        self._env = patch.dict(os.environ, {"INTENT_CLARIFICATION": "on"}, clear=False)
        self._env.start()

    def tearDown(self):
        self._env.stop()

    def test_vague_build_house_needs_clarification(self):
        req = _build_req("建个房子")
        assessment = assess_clarification_needs(req)
        self.assertTrue(assessment.needs_clarification)
        self.assertIn(assessment.reason, ("vague_intent", "generic_typology", "low_confidence"))
        self.assertGreaterEqual(len(assessment.questions_zh), 1)

    def test_specific_landmark_skips_clarification(self):
        req = _build_req("圣家族大教堂")
        assessment = assess_clarification_needs(req)
        self.assertFalse(assessment.needs_clarification)

    def test_generic_cathedral_without_style_needs_clarification(self):
        req = _build_req("生成一个大教堂")
        assessment = assess_clarification_needs(req)
        self.assertTrue(assessment.needs_clarification)
        self.assertEqual(assessment.reason, "generic_typology")

    def test_gothic_style_church_skips_clarification(self):
        req = _build_req("帮我建一个哥特风格教堂，大概30格高")
        assessment = assess_clarification_needs(req)
        self.assertFalse(assessment.needs_clarification)

    def test_follow_up_after_clarification_skips_reask(self):
        history = [
            "Player:建个教堂",
            "AI:需要再确认几件事，这样生成会更靠谱：\n1. 你想做的是哪一类建筑？",
        ]
        req = _build_req("哥特风格，宽30深20高25", chat_history=history)
        self.assertTrue(is_follow_up_to_clarification(req))
        assessment = assess_clarification_needs(req)
        self.assertFalse(assessment.needs_clarification)

    def test_patch_mode_skips_clarification(self):
        req = _build_req("把屋顶换成红色", prompt_mode="PATCH")
        assessment = assess_clarification_needs(req)
        self.assertFalse(assessment.needs_clarification)

    def test_references_skip_clarification(self):
        from app.models.request import ReferenceInput

        req = _build_req(
            "建个东西",
            references=[ReferenceInput(type="web_url", content="https://example.com/ref")],
        )
        assessment = assess_clarification_needs(req)
        self.assertFalse(assessment.needs_clarification)

    def test_maybe_clarification_response_shape(self):
        req = _build_req("建个建筑")
        resp = maybe_clarification_response(req)
        self.assertIsNotNone(resp)
        assert resp is not None
        self.assertEqual(resp["kind"], "clarification")
        self.assertIn("clarification", resp)
        self.assertTrue(resp["clarification"]["needs_clarification"])
        self.assertIn("message_zh", resp["clarification"])

    def test_build_effective_intent_merges_history(self):
        req = _build_req(
            "哥特风格教堂",
            chat_history=["Player:建个教堂", "AI:请问风格？"],
        )
        text = build_effective_intent_text(req)
        self.assertIn("建个教堂", text)
        self.assertIn("哥特风格教堂", text)

    def test_disabled_by_env(self):
        with patch.dict(os.environ, {"INTENT_CLARIFICATION": "off"}):
            req = _build_req("建个房子")
            assessment = assess_clarification_needs(req)
            self.assertFalse(assessment.needs_clarification)


if __name__ == "__main__":
    unittest.main()
