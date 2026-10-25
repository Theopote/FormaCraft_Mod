import copy
import json
from pathlib import Path
import unittest
from app.services.plan_consistency import component_consistency_errors


class PlanConsistencyTest(unittest.TestCase):
    def test_logged_fortress_is_sent_for_repair(self):
        path = Path(__file__).resolve().parents[2] / "src/test/resources/regressions/live-20261025-fortress.json"
        plan = json.loads(path.read_text(encoding="utf-8-sig"))
        errors = component_consistency_errors(plan)
        self.assertTrue(any("main_hall" in e for e in errors))
        corrected = copy.deepcopy(plan)
        for c in corrected["components"]:
            if c.get("component_type") == "MASS_MAIN":
                c["params"]["entrance_type"] = "door"
        self.assertFalse(component_consistency_errors(corrected))

    def test_unhosted_and_gap_plans_are_not_invented_conflicts(self):
        self.assertEqual([], component_consistency_errors({"components": []}))
        self.assertEqual([], component_consistency_errors({"plan_status": "capability_gap"}))
        self.assertEqual([], component_consistency_errors({"components": None}))

    def test_unresolved_contradiction_cannot_fall_back_to_another_building(self):
        from unittest.mock import Mock, patch
        from app.services.llm_plan_repair import repair_llm_plan_validation
        plan = {"components": [
            {"component_type": "MASS_MAIN", "params": {"component_id": "hall", "entrance_type": "none"}},
            {"component_type": "ENTRANCE", "params": {"host_id": "hall"}},
        ]}
        profile = Mock()
        profile.minecraft_strategy.landmark_module = None
        with patch("app.services.llm_plan_repair.validate_llm_plan_dict"), patch(
            "app.services.llm_plan_repair.build_minimal_plan_from_profile"
        ) as fallback:
            out = repair_llm_plan_validation(plan, None, user_text="堡垒入口", building_profile=profile,
                                             normalize_fn=lambda p, req: p)
        fallback.assert_not_called()
        self.assertEqual("capability_gap", out["plan_status"])

    def test_existing_remote_repair_loop_accepts_corrected_entrance(self):
        from unittest.mock import Mock, patch
        from app.services.llm_plan_repair import repair_llm_plan_validation
        plan = {"components": [
            {"component_type": "MASS_MAIN", "params": {"component_id": "hall", "entrance_type": "none"}},
            {"component_type": "ENTRANCE", "params": {"host_id": "hall"}},
        ]}
        corrected = copy.deepcopy(plan)
        corrected["components"][0]["params"]["entrance_type"] = "door"
        response = Mock()
        response.choices = [Mock(message=Mock(content=json.dumps(corrected)))]
        with patch("app.services.llm_plan_repair.validate_llm_plan_dict"), patch(
            "app.models.llm_plan_json_schema.call_chat_with_llm_plan_response_formats",
            return_value=(response, None)
        ) as remote:
            out = repair_llm_plan_validation(plan, None, user_text="堡垒入口", building_profile=None,
                                            normalize_fn=lambda p, req: p, client=object(), model="test",
                                            messages=[{"role": "user", "content": "堡垒入口"}],
                                            call_with_timeout=object())
        self.assertEqual(corrected, out)
        self.assertEqual(1, remote.call_count)
