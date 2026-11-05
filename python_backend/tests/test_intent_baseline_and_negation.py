"""Intent classification fixes from architecture audit: negation, false friends, shared vocab."""

from __future__ import annotations

import json
import unittest
from pathlib import Path

from app.services.plan_semantic_gate import detect_semantic_intent, evaluate_plan_semantics
from app.services.request_intent import (
    attach_request_intent,
    extract_keyword_intent,
    merge_request_intent,
    resolve_request_intent,
    scaffold_plan_for_intent,
)
from app.services.request_intent_vocab import load_request_intent_vocab
from eval.intent_baseline_eval import run_baseline


class IntentBaselineTest(unittest.TestCase):
    def test_shared_vocab_loads(self):
        vocab = load_request_intent_vocab()
        self.assertEqual(vocab.get("schema"), "formacraft.request_intent_vocab.v1")
        tower = (vocab.get("building_classes") or {}).get("tower_like") or {}
        strong = " ".join(tower.get("strong_markers") or [])
        self.assertNotIn("重檐", strong)
        self.assertIn("佛塔", strong)

    def test_baseline_fixture_all_pass(self):
        total, n_fail, failed = run_baseline()
        self.assertGreaterEqual(total, 40, "baseline should keep growing toward ~100")
        self.assertEqual(
            n_fail,
            0,
            "intent baseline failures:\n"
            + "\n".join(f"{cid}: {errs}" for cid, errs in failed),
        )

    def test_audit_false_positives(self):
        cases = [
            ("建一座重檐歇山顶的大殿", "temple"),
            ("建一座20x30的现代图书馆，白墙，不要重檐", "generic"),
            ("建一座带庭院的现代别墅", "generic"),
            ("a modern house with a garden", "generic"),
            ("城堡 with 4 towers 和 庭院", "generic"),
            ("建一座无门槛的亭子 no gable roof", "generic"),
            ("建一座钟楼", "generic"),
            ("建一座灯塔", "generic"),
            ("建一座九层的塔", "generic"),
            ("7-story brick tower", "generic"),
            ("建一座哥特式大教堂", "generic"),
        ]
        for prompt, expected in cases:
            with self.subTest(prompt=prompt):
                intent = extract_keyword_intent(prompt)
                self.assertEqual(intent["building_class"], expected, intent)

    def test_negation_roof_and_threshold(self):
        lib = extract_keyword_intent("建一座20x30的现代图书馆，白墙，不要重檐")
        self.assertIsNone(lib.get("roof_type"))
        self.assertIn("double_eave_hip", lib.get("roof_forbids") or [])

        pavilion = extract_keyword_intent("建一座无门槛的亭子 no gable roof")
        self.assertNotIn("entrance", pavilion.get("forbids") or [])
        self.assertIn("gable", pavilion.get("roof_forbids") or [])

    def test_correct_hall_plan_not_rejected_as_tower(self):
        user = "建一座重檐歇山顶的大殿"
        plan = {
            "mode": "build",
            "style_profile": "DEFAULT",
            "anchor": {"x": 0, "y": 64, "z": 0},
            "plan_meta": {
                "declared_intent": {"building_class": "temple", "confidence": 0.92},
            },
            "components": [
                {
                    "component_type": "FOUNDATION",
                    "slot_id": "hall",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 15, "depth": 11, "height": 1},
                    "features": [],
                    "params": {},
                },
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "hall",
                    "relative_position": {"x": 0, "y": 1, "z": 0},
                    "dimensions": {"width": 15, "depth": 11, "height": 8},
                    "features": ["main_hall", "殿"],
                    "params": {"roof_type": "double_eave_xieshan"},
                },
                {
                    "component_type": "ROOF",
                    "slot_id": "hall",
                    "relative_position": {"x": -1, "y": 9, "z": -1},
                    "dimensions": {"width": 17, "depth": 13, "height": 5},
                    "features": ["重檐歇山"],
                    "params": {"roof_type": "double_eave_xieshan", "host_id": "hall"},
                },
                {
                    "component_type": "ENTRANCE",
                    "slot_id": "hall",
                    "relative_position": {"x": 6, "y": 1, "z": 0},
                    "dimensions": {"width": 3, "depth": 1, "height": 3},
                    "features": [],
                    "params": {},
                },
                {
                    "component_type": "FACADE_WINDOWS",
                    "slot_id": "hall",
                    "relative_position": {"x": 0, "y": 3, "z": 0},
                    "dimensions": {"width": 15, "depth": 11, "height": 2},
                    "features": [],
                    "params": {},
                },
            ],
        }
        attach_request_intent(plan, user)
        self.assertEqual(plan["plan_meta"]["request_intent"]["building_class"], "temple")
        self.assertEqual(detect_semantic_intent(user, plan), "temple")
        errors = evaluate_plan_semantics(plan, user)
        self.assertEqual(errors, [], errors)

    def test_llm_declared_wins_over_weak_keywords(self):
        keyword = extract_keyword_intent("建一座带庭院的现代别墅")
        self.assertEqual(keyword["building_class"], "generic")
        merged = merge_request_intent(
            keyword,
            {"building_class": "generic", "confidence": 0.8},
        )
        self.assertEqual(merged["building_class"], "generic")

        # Strong keyword still overrides a wrong LLM guess.
        pagoda_kw = extract_keyword_intent("生成一座白色藏式佛塔")
        merged_pagoda = merge_request_intent(
            pagoda_kw,
            {"building_class": "generic", "confidence": 0.9},
        )
        self.assertEqual(merged_pagoda["building_class"], "tower_like")
        self.assertEqual(merged_pagoda["source"], "keyword_override")

    def test_low_confidence_does_not_scaffold(self):
        intent = resolve_request_intent("建一座钟楼")
        self.assertEqual(intent["building_class"], "generic")
        self.assertFalse(intent.get("scaffold_eligible"))
        self.assertIsNone(scaffold_plan_for_intent(intent))


class IntentBaselineFixtureIntegrityTest(unittest.TestCase):
    def test_fixture_ids_unique(self):
        path = Path(__file__).resolve().parents[1] / "eval" / "fixtures" / "intent_baseline_v1.json"
        data = json.loads(path.read_text(encoding="utf-8"))
        ids = [c["id"] for c in data["cases"]]
        self.assertEqual(len(ids), len(set(ids)))


if __name__ == "__main__":
    unittest.main()
