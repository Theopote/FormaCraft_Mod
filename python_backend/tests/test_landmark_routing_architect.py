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


if __name__ == "__main__":
    unittest.main()
