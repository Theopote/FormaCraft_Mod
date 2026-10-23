"""Bounded schema critique uses real validation and mocked remote completions."""
import json
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from app.models.building_profile import BuildingProfile
from app.services import llm_plan_repair as repair


class CritiqueLoopTests(unittest.TestCase):
    def setUp(self):
        self.profile = BuildingProfile(query="test hall")
        self.valid = repair.build_minimal_plan_from_profile(self.profile, None, "test hall")
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
        self.assertEqual(repair.repair_llm_plan_validation(self.valid, None, **self.options), self.valid)
        self.call.assert_not_called()

    def test_one_round_preserves_design_before_profile_fallback(self):
        self.call.return_value = self.response(self.valid)
        self.options['building_profile'] = self.profile
        with patch.object(repair, 'build_minimal_plan_from_profile') as fallback:
            self.assertEqual(repair.repair_llm_plan_validation(self.bad, None, **self.options), self.valid)
            fallback.assert_not_called()
        self.assertEqual(self.call.call_count, 1)
        self.assertEqual(self.call.call_args.kwargs['max_tokens'], 4096)
        self.assertLessEqual(self.call.call_args.kwargs['timeout_sec'], 45)

    def test_second_round_receives_latest_plan_and_errors(self):
        intermediate = dict(self.valid, mode='invalid')
        self.call.side_effect = [self.response(intermediate), self.response(self.valid)]
        self.assertEqual(repair.repair_llm_plan_validation(self.bad, None, **self.options), self.valid)
        messages = self.call.call_args.kwargs['messages']
        self.assertEqual(json.loads(messages[-2]['content']), intermediate)
        self.assertIn('mode', messages[-1]['content'])
        self.assertNotIn('anchor: Field required', messages[-1]['content'])

    def test_two_round_cap_reports_latest_errors(self):
        self.call.return_value = self.response(dict(self.valid, mode='invalid'))
        result = repair.repair_llm_plan_validation(self.bad, None, **self.options)
        self.assertEqual(self.call.call_count, 2)
        self.assertEqual(result['plan_status'], 'capability_gap')
        self.assertIn('mode', result['error'])

    def test_invalid_json_and_non_object_are_bounded(self):
        for raw in ('not json', '[]', ''):
            with self.subTest(raw=raw):
                self.call.reset_mock()
                self.call.return_value = (SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=raw))]), 'json_object')
                result = repair.repair_llm_plan_validation(self.bad, None, **self.options)
                self.assertEqual(self.call.call_count, 2)
                self.assertEqual(result['plan_status'], 'capability_gap')

    def test_transport_failure_stops_and_falls_back(self):
        self.call.side_effect = TimeoutError('test timeout')
        self.options['building_profile'] = self.profile
        result = repair.repair_llm_plan_validation(self.bad, None, **self.options)
        self.assertNotEqual(result.get('plan_status'), 'capability_gap')
        self.assertEqual(self.call.call_count, 1)

    def test_disabled_or_offline_uses_profile_without_remote_call(self):
        self.options['building_profile'] = self.profile
        for disabled in (True, False):
            with patch.object(repair, 'LLMPLAN_VALIDATION_REPAIR_LLM', not disabled):
                self.options['client'] = object() if disabled else None
                result = repair.repair_llm_plan_validation(self.bad, None, **self.options)
                self.assertNotEqual(result.get('plan_status'), 'capability_gap')
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
