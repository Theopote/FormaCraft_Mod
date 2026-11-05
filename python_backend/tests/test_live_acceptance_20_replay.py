"""Live acceptance 20 — intent + scaffold/fixture semantics; prompts stay verbatim."""

from __future__ import annotations

import json
import unittest
from pathlib import Path

from app.models.llm_plan import validate_llm_plan_dict
from app.services.generation_capability import apply_stage_b_capability_repair
from app.services.plan_semantic_gate import assert_plan_semantics
from app.services.request_intent import extract_request_intent, scaffold_plan_for_intent, attach_request_intent

_FIXTURE_DIR = (
    Path(__file__).resolve().parents[2]
    / "src"
    / "test"
    / "resources"
    / "regressions"
    / "live_acceptance_20"
)

_CASES = [
    ("01_tibetan_stupa_9", "生成一座白色藏式佛塔，主体尺寸9×9", "tower_like", "tibetan_stupa"),
    ("02_pagoda_3_chongyan", "生成一座三层中式古塔，重檐，顶部有塔刹", "tower_like", "dense_eaves_pagoda"),
    ("03_pagoda_5", "生成一座五层中式宝塔，顶部有塔刹", "tower_like", "dense_eaves_pagoda"),
    ("04_temple_gate", "生成一座中式寺庙山门", "gate", None),
    ("05_paifang", "生成一座中式牌坊", "gate", None),
    ("06_daoguan_single", "生成一座传统道观", "temple", None),
    ("07_temple_compound", "生成一座寺庙建筑群，两进院落与厢房", "temple", None),
    ("08_jiangnan_garden", "生成一座江南园林", "garden", None),
    ("09_japanese_garden", "生成一座日式庭院", "garden", None),
    ("10_huizhou_house", "生成一栋徽派民居，白墙黑瓦", "generic", None),
    ("11_xieshan_stone_shrine", "生成一座歇山顶道观，石砖墙", "temple", None),
    ("12_wudian_hall", "生成一座庑殿顶道观大殿", "temple", None),
    ("13_wood_house_no_windows", "生成一座11×9的两层木屋，不要窗", "generic", None),
    ("14_arched_entrance_house", "生成一座带拱形大门的石砖民居，尺寸13×11", "generic", None),
    ("15_siheyuan_courtyard", "生成一座四合院庭院", "garden", None),
    ("16_pagoda_7_white", "生成一座7×7三层密檐古塔，白墙", "tower_like", "dense_eaves_pagoda"),
    ("17_daoguan_xieshan", "生成一座道观主殿，歇山顶", "temple", None),
    ("18_japanese_tea_garden", "生成一座日式茶室庭院", "garden", None),
    ("19_modern_flat_house", "生成一座简约现代平顶住宅，尺寸12×10", "generic", None),
    ("20_pavilion_garden", "生成一座带亭子的江南园林", "garden", None),
]


class LiveAcceptance20ReplayTest(unittest.TestCase):
    def test_catalog_and_fixtures_exist(self):
        catalog = json.loads((_FIXTURE_DIR / "catalog.json").read_text(encoding="utf-8"))
        self.assertEqual(len(catalog["cases"]), 20)
        for fixture_id, prompt, *_ in _CASES:
            with self.subTest(fixture_id=fixture_id):
                path = _FIXTURE_DIR / f"{fixture_id}.json"
                self.assertTrue(path.is_file(), path)
                plan = json.loads(path.read_text(encoding="utf-8"))
                self.assertEqual(plan["plan_meta"]["acceptance_prompt"], prompt)

    def test_intent_scaffold_semantics_for_twenty_prompts(self):
        for fixture_id, prompt, building_class, typology in _CASES:
            with self.subTest(fixture_id=fixture_id):
                intent = extract_request_intent(prompt)
                self.assertEqual(intent["building_class"], building_class)
                self.assertEqual(intent["original_text"], prompt)
                if typology:
                    self.assertEqual(intent.get("tower_subtype"), typology)

                plan = scaffold_plan_for_intent(
                    intent, anchor={"x": 8, "y": 64, "z": 8}
                )
                if plan is None:
                    plan = json.loads((_FIXTURE_DIR / f"{fixture_id}.json").read_text(encoding="utf-8"))
                attach_request_intent(plan, prompt)
                plan, errors = apply_stage_b_capability_repair(plan, prompt)
                attach_request_intent(plan, prompt)
                validate_llm_plan_dict(plan)
                assert_plan_semantics(plan, prompt)
                self.assertFalse(any("unsupported roof_type" in e for e in errors), errors)

                if typology:
                    params = plan["components"][0]["params"]
                    self.assertEqual(params.get("typology_id"), typology)

                if fixture_id == "02_pagoda_3_chongyan":
                    params = plan["components"][0]["params"]
                    self.assertEqual(params.get("floor_count"), 3)
                    self.assertEqual(params.get("eaves_per_level"), 2)
                if fixture_id == "03_pagoda_5":
                    self.assertEqual(plan["components"][0]["params"].get("floor_count"), 5)
                if fixture_id == "07_temple_compound":
                    self.assertEqual(intent.get("composition"), "compound")
                if fixture_id == "13_wood_house_no_windows":
                    self.assertIn("windows", intent.get("forbids") or [])
                if fixture_id == "19_modern_flat_house":
                    roofs = [c for c in plan["components"] if c.get("component_type") == "ROOF"]
                    self.assertTrue(roofs)
                    self.assertEqual(roofs[0]["params"].get("roof_type"), "flat")


if __name__ == "__main__":
    unittest.main()
