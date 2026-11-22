"""Bounded schema critique uses real validation and mocked remote completions."""
import json
from copy import deepcopy
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from app.models.building_profile import BuildingProfile
from app.services import llm_plan_repair as repair


class CritiqueLoopTests(unittest.TestCase):
    def setUp(self):
        self.profile = BuildingProfile(query="test hall")
        # Minimal plans omit anchor when req has no player pos; schema still requires one.
        self.player_req = SimpleNamespace(
            player=SimpleNamespace(pos=SimpleNamespace(x=12, y=64, z=-8))
        )
        self.valid = repair.build_minimal_plan_from_profile(self.profile, self.player_req, "test hall")
        self.bad = dict(self.valid)
        self.bad.pop("anchor")
        self.options = dict(user_text="test hall", building_profile=None,
                            normalize_fn=lambda plan, req: plan, client=object(),
                            model="test-model", messages=[{"role": "user", "content": "test hall"}],
                            call_with_timeout=lambda fn, timeout: fn())
        self.enabled = patch.object(repair, 'LLMPLAN_VALIDATION_REPAIR_LLM', True)
        self.enabled.start()
        self.addCleanup(self.enabled.stop)
        self.remote = patch('app.models.llm_plan_json_schema.call_chat_with_llm_plan_response_formats')
        self.call = self.remote.start()
        self.addCleanup(self.remote.stop)

    def response(self, data):
        return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=json.dumps(data)))]), 'json_object'

    def test_valid_plan_makes_no_remote_call(self):
        self.assertEqual(
            repair.repair_llm_plan_validation(self.valid, self.player_req, **self.options),
            self.valid,
        )
        self.call.assert_not_called()

    def test_net_height_critique_revalidates_once_and_preserves_failed_plan(self):
        from app.services.building_contract import apply_building_contract

        text = "生成宽12格的建筑，室内净高5格。"
        plan = {**self.valid, "components": [{
            "component_type": "MASS_MAIN", "relative_position": {"x": 0, "y": 0, "z": 0},
            "dimensions": {"width": 12, "depth": 12, "height": 11}, "features": [],
            "params": {"component_id": "atrium", "floor_count": 2, "floor_height": 5,
                       "shape": "circle", "wall_block": "minecraft:smooth_quartz"},
        }]}
        corrected = deepcopy(plan)
        corrected["components"][0]["dimensions"]["height"] = 13
        corrected["components"][0]["params"]["floor_height"] = 6
        opts = {**self.options, "user_text": text,
                "normalize_fn": lambda payload, _: apply_building_contract(payload, text)}
        violates_width = deepcopy(corrected)
        violates_width["components"][0]["dimensions"]["width"] = 14
        changes_design = deepcopy(corrected)
        changes_design["components"][0]["params"].update(shape="rectangle", wall_block="minecraft:bricks")
        adds_program = {**corrected, "plan_program": {"ops": []}}
        adds_skeleton = {**corrected, "plan_skeleton": {"nodes": []}}
        changes_authority = deepcopy(corrected)
        changes_authority.setdefault("plan_meta", {})["allow_compiler_satellites"] = True
        for candidate, succeeds in (
            (corrected, True), (plan, False), (violates_width, False), (changes_design, False),
            (adds_program, False), (adds_skeleton, False), (changes_authority, False),
        ):
            with self.subTest(succeeds=succeeds):
                self.call.reset_mock()
                self.call.return_value = self.response(candidate)
                result = repair.repair_llm_plan_validation(plan, self.player_req, **opts)
                self.call.assert_called_once()
                message = self.call.call_args.kwargs["messages"][-1]["content"]
                self.assertIn("E_NET_HEIGHT_CONFLICT at components[0] (atrium)", message)
                self.assertEqual(13 if succeeds else 11, result["components"][0]["dimensions"]["height"])
                self.assertEqual(12, result["components"][0]["dimensions"]["width"])
                self.assertEqual("circle", result["components"][0]["params"]["shape"])
                self.assertEqual("minecraft:smooth_quartz", result["components"][0]["params"]["wall_block"])
                if succeeds:
                    self.assertNotIn("capability_gap", result)
                else:
                    self.assertEqual("E_NET_HEIGHT_CONFLICT", result["capability_gap"]["code"])

    def test_floor_envelope_repair_rejects_roof_relocation(self):
        from app.services.building_contract import apply_building_contract

        text = "设计一座原创观景厅。"
        body = {"component_type": "MASS_MAIN", "relative_position": {"x": 0, "y": 0, "z": 0},
                "dimensions": {"width": 12, "depth": 12, "height": 9}, "features": [],
                "params": {"component_id": "hall", "floor_count": 2, "floor_height": 5}}
        roof = {"component_type": "ROOF", "relative_position": {"x": 0, "y": 9, "z": 0},
                "dimensions": {"width": 12, "depth": 12, "height": 1}, "features": [],
                "params": {"component_id": "roof", "host_id": "hall", "roof_type": "flat"}}
        plan = {**self.valid, "components": [body, roof]}
        corrected = deepcopy(plan)
        corrected["components"][0]["params"]["floor_height"] = 4
        shifted_roof = deepcopy(corrected)
        shifted_roof["components"][1]["relative_position"]["y"] = 10
        opts = {**self.options, "user_text": text,
                "normalize_fn": lambda payload, _: apply_building_contract(payload, text)}
        for candidate, succeeds in ((corrected, True), (shifted_roof, False)):
            with self.subTest(succeeds=succeeds):
                self.call.reset_mock()
                self.call.return_value = self.response(candidate)
                result = repair.repair_llm_plan_validation(plan, self.player_req, **opts)
                self.call.assert_called_once()
                prompt = self.call.call_args.kwargs["messages"][-1]["content"]
                self.assertIn("E_FLOOR_ENVELOPE at components[0] (hall)", prompt)
                self.assertIn("requires 10 blocks; the body provides 9", prompt)
                self.assertEqual(9, result["components"][1]["relative_position"]["y"])
                self.assertEqual(4 if succeeds else 5, result["components"][0]["params"]["floor_height"])
                if succeeds:
                    self.assertNotIn("capability_gap", result)
                else:
                    self.assertEqual("E_FLOOR_ENVELOPE", result["capability_gap"]["code"])

    def test_floor_repair_can_trade_soft_storey_count_for_a_buildable_plan(self):
        from app.services.building_contract import apply_building_contract

        body = {"component_type": "MASS_MAIN", "relative_position": {"x": 0, "y": 0, "z": 0},
                "dimensions": {"width": 12, "depth": 12, "height": 9}, "features": [],
                "params": {"component_id": "hall", "requirement_scope": "named_office",
                           "floor_count": 2, "floor_height": 5}}
        plan = {**self.valid, "components": [body]}
        corrected = deepcopy(plan)
        corrected["components"][0]["params"]["floor_count"] = 1
        corrected["player_fidelity_notice_zh"] = "为保留高挑大厅，这版调整为一层。"
        for text, soft in (("尽量做成两层办公楼。", True), ("必须做成两层办公楼。", False)):
            with self.subTest(text=text):
                self.call.reset_mock()
                self.call.return_value = self.response(corrected)
                opts = {**self.options, "user_text": text,
                        "normalize_fn": lambda payload, _: apply_building_contract(payload, text)}
                result = repair.repair_llm_plan_validation(plan, self.player_req, **opts)
                self.call.assert_called_once()
                self.assertEqual(1 if soft else 2, result["components"][0]["params"]["floor_count"])
                if soft:
                    self.assertNotIn("capability_gap", result)
                    self.assertEqual(corrected["player_fidelity_notice_zh"], result["player_fidelity_notice_zh"])
                    final = apply_building_contract(result, text, finalize=True)
                    self.assertNotIn("capability_gap", final)
                    self.assertEqual(result["player_fidelity_notice_zh"], final["player_fidelity_notice_zh"])
                else:
                    self.assertEqual("E_FLOOR_ENVELOPE", result["capability_gap"]["code"])

    def test_one_round_preserves_design_before_profile_fallback(self):
        self.call.return_value = self.response(self.valid)
        self.options['building_profile'] = self.profile
        with patch.object(repair, 'build_minimal_plan_from_profile') as fallback:
            self.assertEqual(
                repair.repair_llm_plan_validation(self.bad, self.player_req, **self.options),
                self.valid,
            )
            fallback.assert_not_called()
        self.assertEqual(self.call.call_count, 1)
        self.assertEqual(self.call.call_args.kwargs['max_tokens'], 4096)
        self.assertLessEqual(self.call.call_args.kwargs['timeout_sec'], 45)

    def test_second_round_receives_latest_plan_and_errors(self):
        intermediate = dict(self.valid, mode='invalid')
        self.call.side_effect = [self.response(intermediate), self.response(self.valid)]
        self.assertEqual(
            repair.repair_llm_plan_validation(self.bad, self.player_req, **self.options),
            self.valid,
        )
        messages = self.call.call_args.kwargs['messages']
        self.assertEqual(json.loads(messages[-2]['content']), intermediate)
        self.assertIn('mode', messages[-1]['content'])
        self.assertNotIn('anchor: Field required', messages[-1]['content'])

    def test_two_round_cap_reports_latest_errors(self):
        self.call.return_value = self.response(dict(self.valid, mode='invalid'))
        result = repair.repair_llm_plan_validation(self.bad, self.player_req, **self.options)
        self.assertEqual(self.call.call_count, 2)
        self.assertEqual(result['plan_status'], 'capability_gap')
        self.assertIn('mode', result['error'])

    def test_invalid_json_and_non_object_are_bounded(self):
        for raw in ('not json', '[]', ''):
            with self.subTest(raw=raw):
                self.call.reset_mock()
                self.call.return_value = (SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=raw))]), 'json_object')
                result = repair.repair_llm_plan_validation(self.bad, self.player_req, **self.options)
                self.assertEqual(self.call.call_count, 2)
                self.assertEqual(result['plan_status'], 'capability_gap')

    def test_transport_failure_stops_without_house_kit_swap(self):
        self.call.side_effect = TimeoutError('test timeout')
        self.options['building_profile'] = self.profile
        result = repair.repair_llm_plan_validation(self.bad, self.player_req, **self.options)
        # Open-world path: preserve design — capability_gap instead of silent MASS_MAIN kit.
        self.assertEqual(result.get('plan_status'), 'capability_gap')
        self.assertEqual(self.call.call_count, 1)

    def test_disabled_or_offline_emits_gap_without_remote_call(self):
        self.options['building_profile'] = self.profile
        for disabled in (True, False):
            with patch.object(repair, 'LLMPLAN_VALIDATION_REPAIR_LLM', not disabled):
                self.options['client'] = object() if disabled else None
                result = repair.repair_llm_plan_validation(self.bad, self.player_req, **self.options)
                self.assertEqual(result.get('plan_status'), 'capability_gap')
        self.call.assert_not_called()

    def test_response_adapter_forwards_completion_budget(self):
        self.remote.stop()
        from unittest.mock import Mock
        from app.models.llm_plan_json_schema import call_chat_with_llm_plan_response_formats
        client = Mock()
        client.with_options.return_value = client
        call_chat_with_llm_plan_response_formats(
            client, model="test-model", messages=[], temperature=0.2,
            call_with_timeout=lambda fn, timeout: fn(), timeout_sec=20,
            prefer_json_object=True, max_tokens=4096)
        self.assertEqual(client.chat.completions.create.call_args.kwargs['max_tokens'], 4096)
        client.with_options.assert_called_once_with(timeout=20, max_retries=0)
