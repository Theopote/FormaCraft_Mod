"""Architect-named stadium prompts must not route to birds_nest."""

import unittest

from app.services.landmark_routing_policy import resolve_for_user_intent


class ArchitectLandmarkRoutingTest(unittest.TestCase):
    def test_zaha_stadium_no_birds_nest(self):
        d = resolve_for_user_intent("扎哈设计的西安体育馆")
        self.assertIsNone(d)

    def test_modern_stadium_still_suggests_birds_nest(self):
        d = resolve_for_user_intent("现代风格的体育馆")
        self.assertIsNotNone(d)
        self.assertEqual(d.module_id, "birds_nest_stadium")

    def test_explicit_birds_nest_still_mandatory(self):
        d = resolve_for_user_intent("建造鸟巢体育馆")
        self.assertIsNotNone(d)
        self.assertEqual(d.tier.value, "mandatory")

    def test_variation_with_explicit_birds_nest_still_mandatory(self):
        """「不一样」等多样化词不应否决已指名的鸟巢请求。"""
        d = resolve_for_user_intent("给我做个不一样的鸟巢体育馆")
        self.assertIsNotNone(d)
        self.assertEqual(d.module_id, "birds_nest_stadium")
        self.assertEqual(d.tier.value, "mandatory")
        self.assertEqual(d.reason, "explicit_birds_nest")

    def test_variation_without_landmark_blocks_typology(self):
        from app.services.landmark_routing_policy import is_variation_intent

        self.assertTrue(is_variation_intent("给我做个不一样的现代椭圆体育场"))
        d = resolve_for_user_intent("给我做个不一样的现代椭圆体育场")
        self.assertIsNone(d)


if __name__ == "__main__":
    unittest.main()
