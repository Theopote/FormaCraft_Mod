"""Batch 4 — seven acceptance prompts: scaffold + semantics; prompts stay verbatim."""

from __future__ import annotations

import json
import unittest
from pathlib import Path

from app.models.llm_plan import validate_llm_plan_dict
from app.services.generation_capability import apply_stage_b_capability_repair, normalize_roof_type
from app.services.plan_semantic_gate import assert_plan_semantics
from app.services.request_intent import extract_request_intent, scaffold_plan_for_intent, attach_request_intent

_CASES = [
    ("01_tibetan_stupa", "生成一座白色藏式佛塔，主体尺寸7×7", "tower_like", "tibetan_stupa"),
    ("02_dense_eaves_pagoda", "生成一座三层中式古塔，重檐，顶部有塔刹", "tower_like", "dense_eaves_pagoda"),
    ("03_temple_daoguan", "生成一座传统道观", "temple", None),
    ("04_temple_gate", "生成一座中式寺庙山门", "gate", None),
    ("05_huizhou_house", "生成一栋徽派民居，白墙黑瓦", "generic", None),
    ("06_jiangnan_garden", "生成一座江南园林", "garden", None),
    ("07_japanese_garden", "生成一座日式庭院", "garden", None),
]

_FIXTURE_DIR = (
    Path(__file__).resolve().parents[2]
    / "src"
    / "test"
    / "resources"
    / "regressions"
    / "building_quality_recovery_7"
)


class BuildingQualityRecovery7ReplayTest(unittest.TestCase):
    def test_fixtures_keep_verbatim_prompts(self):
        for fixture_id, prompt, *_ in _CASES:
            with self.subTest(fixture_id=fixture_id):
                path = _FIXTURE_DIR / f"{fixture_id}.json"
                self.assertTrue(path.is_file(), path)
                plan = json.loads(path.read_text(encoding="utf-8"))
                self.assertEqual(plan["plan_meta"]["acceptance_prompt"], prompt)

    def test_scaffold_and_semantics_for_seven_prompts(self):
        for fixture_id, prompt, building_class, typology in _CASES:
            with self.subTest(fixture_id=fixture_id):
                intent = extract_request_intent(prompt)
                self.assertEqual(intent["building_class"], building_class)
                self.assertEqual(intent["original_text"], prompt)
                plan = scaffold_plan_for_intent(
                    intent, anchor={"x": 8, "y": 64, "z": 8}
                )
                if plan is None:
                    # generic residential: load frozen fixture
                    plan = json.loads((_FIXTURE_DIR / f"{fixture_id}.json").read_text(encoding="utf-8"))
                attach_request_intent(plan, prompt)
                validate_llm_plan_dict(plan)
                assert_plan_semantics(plan, prompt)
                if typology:
                    params = plan["components"][0]["params"]
                    self.assertEqual(params.get("typology_id"), typology)
                    self.assertEqual(params.get("typology"), typology)
                if fixture_id == "02_dense_eaves_pagoda":
                    params = plan["components"][0]["params"]
                    self.assertEqual(params.get("floor_count"), 3)
                    self.assertEqual(params.get("eaves_per_level"), 2)
                if fixture_id == "07_japanese_garden":
                    self.assertEqual(normalize_roof_type("irimoya"), "xieshan")
                    frozen = json.loads((_FIXTURE_DIR / f"{fixture_id}.json").read_text(encoding="utf-8"))
                    repaired, errors = apply_stage_b_capability_repair(frozen, prompt)
                    self.assertFalse(any("unsupported roof_type" in e for e in errors), errors)
                    roofs = [
                        c for c in repaired["components"]
                        if c.get("component_type") == "ROOF"
                    ]
                    if roofs:
                        self.assertEqual(roofs[0]["params"].get("roof_type"), "xieshan")


if __name__ == "__main__":
    unittest.main()
