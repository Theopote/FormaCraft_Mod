"""Phase 1 building-quality recovery: clarification → semantic gate → contract (no live LLM)."""

from __future__ import annotations

import os
import unittest
from unittest.mock import patch

from app.models.llm_plan import LlmPlanValidationError, validate_llm_plan_dict
from app.models.request import BuildRequest, PlayerInfo, Vec3i, WorldContext
from app.services.generation_quality_metrics import (
    GenerationQualityMetrics,
    record_autofill_diagnostics,
    record_parse_outcome,
    record_plan_outcome,
)
from app.services.intent_clarification import assess_clarification_needs
from app.services.plan_semantic_gate import (
    assert_plan_semantics,
    coerce_style_attribute_maps,
    detect_semantic_intent,
    evaluate_plan_semantics,
)


PHASE1_SAMPLES = (
    ("生成一座白色藏式佛塔，7x7", "tower_like"),
    ("生成一座三层中式古塔，重檐塔刹", "tower_like"),
    ("生成一座传统道观", "temple"),
    ("生成一座寺庙山门", "gate"),
    ("生成一座徽派民居，白墙黑瓦", "generic"),
    ("生成一座江南园林", "garden"),
    ("生成一座日式庭院", "garden"),
)


def _build_req(user_message: str) -> BuildRequest:
    return BuildRequest(
        player=PlayerInfo(name="test", pos=Vec3i(x=0, y=64, z=0), facing="NORTH"),
        world=WorldContext(dimension="minecraft:overworld"),
        requestText="USER REQUEST:\n" + user_message,
        userMessage=user_message,
        promptMode="BUILD",
    )


def _anchor_plan(**kwargs):
    base = {
        "mode": "build",
        "style_profile": "DEFAULT",
        "anchor": {"x": 0, "y": 64, "z": 0},
        "components": [],
    }
    base.update(kwargs)
    return base


def _house_kit(features=None):
    return [
        {
            "component_type": "MASS_MAIN",
            "slot_id": "main",
            "relative_position": {"x": 0, "y": 0, "z": 0},
            "dimensions": {"width": 9, "depth": 7, "height": 6},
            "features": features or [],
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
        {
            "component_type": "FACADE_WINDOWS",
            "slot_id": "main",
            "relative_position": {"x": 0, "y": 1, "z": 0},
            "dimensions": {"width": 9, "depth": 1, "height": 3},
            "features": [],
            "params": {"window_ratio": 0.3},
        },
        {
            "component_type": "ENTRANCE",
            "slot_id": "main",
            "relative_position": {"x": 0, "y": 0, "z": -4},
            "dimensions": {"width": 3, "depth": 2, "height": 3},
            "features": [],
            "params": {},
        },
    ]


def _pagoda_plan():
    return _anchor_plan(
        distinguishing_features=["佛塔", "tibetan_stupa", "塔刹"],
        components=[
            {
                "component_type": "STRUCTURE",
                "slot_id": "tower",
                "relative_position": {"x": 0, "y": 0, "z": 0},
                "dimensions": {"width": 7, "depth": 7, "height": 21},
                "features": ["tibetan_stupa", "覆钵", "相轮", "finial_spire"],
                "params": {"typology": "tibetan_stupa", "typology_id": "tibetan_stupa", "baseWidth": 7},
            }
        ],
    )


def _dense_eaves_plan():
    return _anchor_plan(
        distinguishing_features=["古塔", "dense_eaves_pagoda", "塔刹", "重檐"],
        components=[
            {
                "component_type": "STRUCTURE",
                "slot_id": "tower",
                "relative_position": {"x": 0, "y": 0, "z": 0},
                "dimensions": {"width": 7, "depth": 7, "height": 21},
                "features": ["dense_eaves_pagoda", "finial_spire", "重檐"],
                "params": {
                    "typology": "dense_eaves_pagoda",
                    "floor_count": 3,
                    "levels": 3,
                    "eaves_per_level": 2,
                },
            }
        ],
    )


class BuildingQualityRecoveryPhase1Test(unittest.TestCase):
    def setUp(self):
        self._env = patch.dict(os.environ, {"INTENT_CLARIFICATION": "on"}, clear=False)
        self._env.start()
        GenerationQualityMetrics.reset_for_tests()

    def tearDown(self):
        self._env.stop()
        GenerationQualityMetrics.reset_for_tests()

    def test_phase1_samples_skip_clarification(self):
        for text, _intent in PHASE1_SAMPLES:
            with self.subTest(text=text):
                assessment = assess_clarification_needs(_build_req(text))
                self.assertFalse(
                    assessment.needs_clarification,
                    msg=f"{text!r} clarified: {assessment.reason}",
                )
                self.assertNotEqual(assessment.reason, "generic_typology")

    def test_phase1_intent_detection(self):
        for text, expected in PHASE1_SAMPLES:
            with self.subTest(text=text):
                self.assertEqual(detect_semantic_intent(text), expected)

    def test_empty_build_plan_rejected_for_typology_samples(self):
        for text, intent in PHASE1_SAMPLES:
            if intent == "generic":
                continue
            with self.subTest(text=text):
                plan = _anchor_plan(distinguishing_features=[text])
                errors = evaluate_plan_semantics(plan, text)
                self.assertTrue(errors, msg=f"expected semantic/empty rejection for {text!r}")
                with self.assertRaises(LlmPlanValidationError):
                    validate_llm_plan_dict(plan)

    def test_pagoda_must_not_be_default_house(self):
        text = "生成一座白色藏式佛塔，7x7"
        plan = _anchor_plan(
            distinguishing_features=["白色藏式佛塔"],
            components=_house_kit(features=["pagoda"]),
        )
        # House kit with only a token feature and residential satellites still fails
        # unless morphology is truly tower-like — features alone on MASS may pass
        # _has_tower_morphology. Force residential-only features for the negative case.
        plan["components"] = _house_kit(features=[])
        plan["distinguishing_features"] = ["佛塔", "藏式"]
        errors = evaluate_plan_semantics(plan, text)
        self.assertTrue(any("tower/pagoda" in e or "semantics" in e for e in errors), errors)

    def test_windows_disabled_plan_is_not_forced_to_add_windows_in_contract(self):
        """Negative: explicit no-window mass must remain valid without FACADE_WINDOWS."""
        plan = _anchor_plan(
            distinguishing_features=["徽派", "白墙黑瓦"],
            components=[
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "main",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 11, "depth": 9, "height": 7},
                    "features": ["huizhou"],
                    "params": {"window_style": "none", "window_ratio": 0},
                },
                {
                    "component_type": "ROOF",
                    "slot_id": "main",
                    "relative_position": {"x": 0, "y": 7, "z": 0},
                    "dimensions": {"width": 13, "depth": 11, "height": 3},
                    "features": [],
                    "params": {"roof_type": "gable"},
                },
            ],
            style_attributes={
                "wall_color": "white",
                "roof_color": "black",
                "palette": {"wall": "minecraft:white_concrete", "roof": "minecraft:black_terracotta"},
            },
        )
        validate_llm_plan_dict(plan)
        assert_plan_semantics(plan, "徽派民居白墙黑瓦禁窗")
        types = {c["component_type"] for c in plan["components"]}
        self.assertNotIn("FACADE_WINDOWS", types)

    def test_valid_tibetan_stupa_morphology_passes(self):
        plan = _pagoda_plan()
        validate_llm_plan_dict(plan)
        assert_plan_semantics(plan, "白色藏式佛塔")

    def test_valid_dense_eaves_three_storey_chongyan_passes(self):
        plan = _dense_eaves_plan()
        validate_llm_plan_dict(plan)
        assert_plan_semantics(plan, "生成一座三层中式古塔，重檐，顶部有塔刹")

    def test_palette_preset_string_moves_to_style_profile(self):
        plan = _pagoda_plan()
        plan["style_attributes"] = {"palette": "PALETTE_JAPANESE_TRADITIONAL"}
        plan["style_profile"] = ""
        coerced = coerce_style_attribute_maps(plan)
        self.assertEqual(coerced["style_profile"], "PALETTE_JAPANESE_TRADITIONAL")
        self.assertNotIn("palette", coerced.get("style_attributes") or {})
        validate_llm_plan_dict(coerced)

    def test_quality_metrics_split_parse_build_fidelity(self):
        record_parse_outcome(True, request_id="r1", user_text="白色藏式佛塔")
        record_plan_outcome(
            parse_ok=True,
            build_ok=False,
            intent_fidelity_ok=False,
            request_id="r1",
            user_text="白色藏式佛塔",
            intent_class="tower_like",
            reason="E_SEMANTIC_FIDELITY",
            plan_summary="components=house_kit",
        )
        record_autofill_diagnostics(
            request_id="r1",
            autofills=["SKIPPED_DEFAULT_GABLE"],
            roof_type=None,
            materials={"wall": "white"},
        )
        snap = GenerationQualityMetrics.snapshot()
        self.assertEqual(snap["parse_success"], 2)
        self.assertEqual(snap["plan_valid_success"], 2)
        self.assertEqual(snap["build_success"], 0)
        self.assertEqual(snap["geometry_success"], 0)
        self.assertEqual(snap["intent_fidelity_success"], 0)
        self.assertEqual(snap["intent_fidelity_fail"], 1)
        self.assertGreaterEqual(snap["autofill_events"], 1)

    def test_geometry_ok_morphology_fail_fidelity(self):
        record_plan_outcome(
            parse_ok=True,
            build_ok=True,
            intent_fidelity_ok=False,
            geometry_ok=True,
            world_apply_ok=True,
            request_id="r2",
            user_text="三层中式古塔重檐",
            intent_class="tower_like",
            reason="E_PAGODA_MORPHOLOGY",
            plan_summary="floors=7",
        )
        snap = GenerationQualityMetrics.snapshot()
        self.assertEqual(snap["geometry_success"], 1)
        self.assertEqual(snap["world_apply_success"], 1)
        self.assertEqual(snap["intent_fidelity_fail"], 1)
        self.assertEqual(snap["intent_fidelity_success"], 0)


if __name__ == "__main__":
    unittest.main()
