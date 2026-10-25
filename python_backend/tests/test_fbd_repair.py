import json
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.main import app
from app.models.request_adapter import FormaRequestAdapter
from app.services.fbd_repair import propose_fbd_repair, FbdRepairPatch

ROOT = Path(__file__).resolve().parents[2]
FIELD = '/components/0/params/fbd_program/functions/0/body/1/params/type'


class FbdRepairTest(unittest.TestCase):
    def setUp(self):
        self.plan = json.loads((ROOT / 'src/main/resources/assets/formacraft/fbd/examples/repeated_pavilions.llmplan.json').read_text(encoding='utf-8'))
        self.plan['components'][0]['params']['fbd_program']['functions'][0]['body'][1]['params']['type'] = 'BANANA'
        self.request = {'requestText': 'legacy schema', 'userMessage': '使用 FBD 建造三座亭子，seed=42',
                        'playerPos': {'x': 0, 'y': 64, 'z': 0}, 'facing': 'WEST', 'apiKey': 'test-secret',
                        'model': 'original-model', 'chatHistory': ['old irrelevant chat']}
        self.gap = {'code': 'E_FBD_INVALID_ENUM', 'path': FIELD, 'message': 'invalid roof type',
                    'details': {'node': 'roof', 'stage': 'typecheck', 'path': '/functions/0/body/1/params/type'}}

    def test_single_provider_call_has_no_hidden_retries_and_no_credentials_in_prompt(self):
        client = Mock()
        client.with_options.return_value.chat.completions.create.return_value = SimpleNamespace(
            choices=[SimpleNamespace(message=SimpleNamespace(content=json.dumps({'path': FIELD, 'value': 'FLAT'})))])
        req = FormaRequestAdapter(**self.request).to_build_request()
        with patch('app.services.ai_planner.get_client', return_value=client), \
             patch('app.services.ai_planner._LLMPLAN_MODEL_OVERRIDE', ''), \
             patch('app.services.ai_planner._call_with_timeout', side_effect=lambda fn, seconds: fn()) as timeout:
            result = propose_fbd_repair(req, self.plan, self.gap, 1)
        self.assertEqual({'path': FIELD, 'value': 'FLAT'}, result)
        client.with_options.assert_called_once_with(timeout=40, max_retries=0)
        kwargs = client.with_options.return_value.chat.completions.create.call_args.kwargs
        self.assertEqual('original-model', kwargs['model'])
        self.assertEqual(1024, kwargs['max_tokens'])
        self.assertEqual(45, timeout.call_args.args[1])
        prompt = json.dumps(kwargs['messages'])
        self.assertIn('Java repair round 1/2', prompt)
        self.assertIn('WEST', prompt)
        self.assertNotIn('test-secret', prompt)
        self.assertNotIn('old irrelevant chat', prompt)
        self.assertNotIn('legacy schema', prompt)

    def test_terminal_diagnostics_do_not_call_model(self):
        req = FormaRequestAdapter(**self.request).to_build_request()
        with patch('app.services.ai_planner.get_client') as client:
            for code in ('E_FBD_BUDGET_EXCEEDED', 'E_FBD_PARAM_OUT_OF_RANGE', 'E_FBD_UNKNOWN_VARIABLE'):
                with self.subTest(code=code), self.assertRaises(ValueError):
                    propose_fbd_repair(req, self.plan, {'code': code}, 1)
        client.assert_not_called()

    def test_patch_schema_rejects_whole_plans_complex_values_nonfinite_and_extra_fields(self):
        for value in (self.plan, [], {'path': FIELD, 'value': []}, {'path': FIELD, 'value': float('nan')},
                      {'path': FIELD, 'value': True}, {'path': FIELD, 'value': 'FLAT', 'other': 'drop program'},
                      {'path': '/components/0/dimensions/width', 'value': 1}):
            with self.subTest(value=type(value).__name__), self.assertRaises(ValidationError):
                FbdRepairPatch.model_validate(value)

    def test_http_route_preserves_round_and_structured_java_diagnostic(self):
        with patch('app.routes.fbd_repair.propose_fbd_repair', return_value={'path': FIELD, 'value': 'FLAT'}) as propose:
            response = TestClient(app).post('/fbd/repair', json={'request': self.request, 'plan': self.plan,
                                                               'diagnostic': self.gap, 'round': 2})
        self.assertEqual(200, response.status_code)
        self.assertEqual(self.gap, propose.call_args.args[2])
        self.assertEqual(2, propose.call_args.args[3])
        self.assertEqual({'path': FIELD, 'value': 'FLAT'}, response.json())

    def test_invalid_round_is_rejected_and_provider_details_do_not_leak(self):
        payload = {'request': self.request, 'plan': self.plan, 'diagnostic': self.gap, 'round': 3}
        self.assertEqual(422, TestClient(app).post('/fbd/repair', json=payload).status_code)
        payload['round'] = 1
        with patch('app.routes.fbd_repair.propose_fbd_repair', side_effect=RuntimeError('test-secret')):
            response = TestClient(app).post('/fbd/repair', json=payload)
        self.assertEqual(502, response.status_code)
        self.assertNotIn('test-secret', response.text)
