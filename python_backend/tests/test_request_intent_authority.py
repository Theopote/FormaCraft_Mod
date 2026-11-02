"""Authoritative request_intent must catch LLM intent loss and scaffold recovery."""

from __future__ import annotations

import unittest

from app.models.llm_plan import validate_llm_plan_dict
from app.services.plan_semantic_gate import assert_plan_semantics, detect_semantic_intent, evaluate_plan_semantics
from app.services.request_intent import (
    attach_request_intent,
    extract_request_intent,
    scaffold_plan_for_intent,
)


class RequestIntentAuthorityTest(unittest.TestCase):
    def test_pagoda_intent_survives_when_llm_emits_plain_mass(self):
        user = "生成一座白色藏式佛塔，主体尺寸7×7"
        intent = extract_request_intent(user)
        self.assertEqual(intent["building_class"], "tower_like")
        self.assertEqual(intent["footprint"]["width"], 7)

        # LLM forgot all pagoda markers — only a house mass.
        plan = {
            "mode": "build",
            "style_profile": "DEFAULT",
            "anchor": {"x": 0, "y": 64, "z": 0},
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "main",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 9, "depth": 7, "height": 6},
                    "features": [],
                    "params": {"roof_type": "gable"},
                },
                {
                    "component_type": "ROOF",
                    "slot_id": "main",
                    "relative_position": {"x": 0, "y": 6, "z": 0},
                    "dimensions": {"width": 11, "depth": 9, "height": 3},
                    "features": [],
                    "params": {"roof_type": "gable"},
                },
            ],
        }
        attach_request_intent(plan, user)
        self.assertEqual(detect_semantic_intent("", plan), "tower_like")
        errors = evaluate_plan_semantics(plan, "")
        self.assertTrue(errors, "must reject house kit despite empty user_text when request_intent is set")

    def test_crown_on_house_is_not_pagoda_morphology(self):
        user = "生成一座中式古塔，重檐塔刹"
        plan = attach_request_intent(
            {
                "mode": "build",
                "style_profile": "DEFAULT",
                "anchor": {"x": 0, "y": 64, "z": 0},
                "components": [
                    {
                        "component_type": "MASS_MAIN",
                        "slot_id": "main",
                        "relative_position": {"x": 0, "y": 0, "z": 0},
                        "dimensions": {"width": 9, "depth": 7, "height": 6},
                        "features": [],
                        "params": {},
                    },
                    {
                        "component_type": "CROWN",
                        "slot_id": "main",
                        "relative_position": {"x": 0, "y": 6, "z": 0},
                        "dimensions": {"width": 3, "depth": 3, "height": 2},
                        "features": ["finial"],
                        "params": {},
                    },
                    {
                        "component_type": "ROOF",
                        "slot_id": "main",
                        "relative_position": {"x": 0, "y": 6, "z": 0},
                        "dimensions": {"width": 11, "depth": 9, "height": 3},
                        "features": [],
                        "params": {"roof_type": "gable"},
                    },
                ],
            },
            user,
        )
        errors = evaluate_plan_semantics(plan, user)
        self.assertTrue(any("tower/pagoda" in e or "semantics" in e for e in errors), errors)

    def test_scaffold_pagoda_passes_validation(self):
        intent = extract_request_intent("生成一座白色藏式佛塔，7x7")
        plan = scaffold_plan_for_intent(intent)
        self.assertIsNotNone(plan)
        attach_request_intent(plan, intent["original_text"])
        validate_llm_plan_dict(plan)
        assert_plan_semantics(plan, intent["original_text"])

    def test_explicit_xieshan_marked(self):
        intent = extract_request_intent("生成歇山顶徽派民居，白墙黑瓦")
        self.assertEqual(intent["roof_type"], "xieshan")
        self.assertTrue(intent["roof_explicit"])
        self.assertEqual(intent["materials"].get("wall"), "minecraft:white_concrete")

    def test_acceptance_checklist_classes(self):
        cases = [
            ("生成一座白色藏式佛塔，主体尺寸7×7", "tower_like"),
            ("生成一座三层中式古塔，重檐，顶部有塔刹", "tower_like"),
            ("生成一座传统道观", "temple"),
            ("生成一座中式寺庙山门", "gate"),
            ("生成一栋徽派民居，白墙黑瓦", "generic"),
            ("生成一座江南园林", "garden"),
            ("生成一座日式庭院", "garden"),
        ]
        for text, expected in cases:
            with self.subTest(text=text):
                self.assertEqual(extract_request_intent(text)["building_class"], expected)


if __name__ == "__main__":
    unittest.main()
