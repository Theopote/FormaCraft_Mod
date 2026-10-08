import json
from pathlib import Path
import unittest

from app.services.building_contract import apply_building_contract, extract_requirements

ROOT = Path(__file__).parent / "fixtures/style_acceptance_oct08"


class StyleAcceptanceRegressionTest(unittest.TestCase):
    def test_all_five_logged_plans_have_no_false_contract_conflicts(self):
        texts = json.loads((ROOT / "requests.json").read_text(encoding="utf-8"))
        self.assertEqual(len(texts), 5)
        for index, text in enumerate(texts, 1):
            with self.subTest(case=index):
                plan = json.loads((ROOT / f"case_{index}.json").read_text(encoding="utf-8"))
                result = apply_building_contract(plan, text)
                contract = result["proportion_hints"]["building_contract"]
                self.assertEqual(contract["diagnostics"], [])
                for requirement in contract["requirements"]:
                    if requirement["property"] in ("roof_type", "window_style", "gable_windows"):
                        self.assertEqual(requirement["status"], "planned")
                for body in result["components"]:
                    if body["component_type"] == "MASS_MAIN":
                        self.assertNotEqual(body["params"].get("window_style"), "none")
                self.assertEqual(result["components"], apply_building_contract(result, text)["components"])

    def test_negated_roof_is_not_a_positive_requirement(self):
        for text in ("使用双坡屋顶，不使用平屋顶", "采用双坡屋顶，不采用平屋顶", "不要平屋顶，使用双坡屋顶"):
            roofs = [r["value"] for r in extract_requirements(text) if r["property"] == "roof_type"]
            self.assertEqual(roofs, ["gable"])

    def test_gable_only_opt_out_survives_punctuation_and_chat_wrap(self):
        requirements = extract_requirements("前后山墙封闭，不开窗；左右侧墙设置玻璃\n窗。")
        self.assertFalse(any(r["property"] == "window_style" for r in requirements))
        self.assertTrue(any(r["property"] == "gable_windows" and r["value"] is False for r in requirements))
        self.assertTrue(any(r["property"] == "window_style" for r in extract_requirements("整栋不开窗")))

    def test_cardinal_scopes_bind_world_positions_not_array_order(self):
        plan = json.loads((ROOT / "case_5.json").read_text(encoding="utf-8"))
        plan["components"].reverse()
        text = "西侧住宅使用平屋顶。东侧住宅使用双坡屋\n顶。"
        result = apply_building_contract(plan, text)
        bodies = {c["params"]["component_id"]: c["params"]["requirement_scope"] for c in result["components"] if c["component_type"] == "MASS_MAIN"}
        self.assertEqual(bodies["mass_main_west"], "building_west")
        self.assertEqual(bodies["mass_main_east"], "building_east")
        self.assertEqual(result["proportion_hints"]["building_contract"]["diagnostics"], [])


if __name__ == "__main__":
    unittest.main()
