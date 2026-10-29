"""Bare church prompts must not expand into gothic style tokens."""
import unittest

from app.services.style_profile_registry import _expand_query_tokens
from app.services.style_identity import matched_style_ids, canonical_style_id
from app.services.archetype_detector import detect_archetype_local
from app.services.keyword_culture_retriever import resolve_landmark_module_routing


class ChurchNotForcedGothicTest(unittest.TestCase):
    def test_bare_church_tokens_are_religious_not_gothic(self):
        for prompt in (
            "生成一座米兰大教堂",
            "生成一座巴洛克教堂",
            "生成一座现代教堂",
            "生成一座罗马式教堂",
            "生成一座小教堂",
        ):
            with self.subTest(prompt=prompt):
                tokens = set(_expand_query_tokens(prompt))
                self.assertNotIn("gothic", tokens, prompt)
                self.assertTrue({"church", "religious", "sacred"} & tokens, prompt)
                self.assertNotIn("Gothic_Cathedral", matched_style_ids(prompt))
                self.assertIsNone(resolve_landmark_module_routing(prompt))

    def test_explicit_gothic_still_routes(self):
        prompt = "生成一座哥特大教堂"
        tokens = set(_expand_query_tokens(prompt))
        self.assertIn("gothic", tokens)
        match = detect_archetype_local(prompt)
        self.assertIsNotNone(match)
        self.assertEqual(match.id, "gothic_cathedral")
        routing = resolve_landmark_module_routing(prompt)
        self.assertEqual(routing.get("typologyId"), "gothic_cathedral_hall")
        self.assertIn("Gothic_Cathedral", matched_style_ids(prompt))

    def test_cathedral_alias_no_longer_equals_gothic_identity(self):
        self.assertNotEqual(canonical_style_id("Cathedral"), "Gothic_Cathedral")


if __name__ == "__main__":
    unittest.main()
