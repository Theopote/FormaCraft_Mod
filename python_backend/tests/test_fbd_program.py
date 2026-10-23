"""FBD keeps its authority and AST through the existing backend path."""
from copy import deepcopy
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from app.models.llm_plan import LlmPlanValidationError, validate_llm_plan_dict
from app.services.fbd_capabilities import fbd_prompt_block

ROOT = Path(__file__).resolve().parents[2]


class FbdProgramTest(unittest.TestCase):
    def plan(self):
        return {'mode': 'build', 'anchor': {'x': 0, 'y': 64, 'z': 0}, 'style_profile': 'DEFAULT',
                'components': [{'component_type': 'ASSEMBLY', 'relative_position': {'x': 0, 'y': 0, 'z': 0},
                                'dimensions': {'width': 7, 'depth': 7, 'height': 8}, 'features': [],
                                'params': {'component_id': 'procedural', 'fbd_program': {'dialect': 'fbd/0', 'seed': 1,
                                    'main': [{'stmt': 'emit', 'id': 'shell', 'op': 'SHELL_BOX',
                                              'params': {'w': 7, 'd': 7, 'h': 6}}]}}}]}

    def test_program_is_not_interpreted_as_missing_assembly_or_given_a_preset(self):
        from app.services.assembly_plan_repair import normalize_and_validate_assembly_plan
        p = self.plan()
        original = deepcopy(p['components'][0]['params']['fbd_program'])
        for text in ('procedural pavilion', 'spiral tower assembly', 'bridge'):
            actual, issues = normalize_and_validate_assembly_plan(deepcopy(p), text)
            self.assertFalse(issues)
            self.assertEqual(original, actual['components'][0]['params']['fbd_program'])
            self.assertNotIn('assembly', actual['components'][0]['params'])
            validate_llm_plan_dict(actual)

    def test_conflicting_authorities_and_reserved_metadata_are_rejected(self):
        for field in ('assembly', 'ops', 'graph', 'macro', 'preset', 'skeleton', '_server_fbd_compilation'):
            p = self.plan()
            p['components'][0]['params'][field] = {}
            with self.subTest(field=field), self.assertRaises(LlmPlanValidationError):
                validate_llm_plan_dict(p)
        p = self.plan()
        p['components'][0]['component_type'] = 'MASS_MAIN'
        with self.assertRaises(LlmPlanValidationError):
            validate_llm_plan_dict(p)

    def test_fbd_prompt_is_opt_in_and_uses_correct_shared_ranges(self):
        with patch.dict('os.environ', {'LLMPLAN_FBD': 'off'}):
            self.assertEqual('', fbd_prompt_block())
        with patch.dict('os.environ', {'LLMPLAN_FBD': 'on'}):
            prompt = fbd_prompt_block()
            self.assertIn('"twistTurns":{"type":"scalar","min":-2,"max":2}', prompt)
            self.assertIn('no queries', prompt)
            self.assertIn('params.fbd_program', prompt)

    def test_generated_schema_stays_in_sync_with_shared_signatures(self):
        spec = importlib.util.spec_from_file_location('export_fbd_schema', ROOT / 'python_backend/tools/export_fbd_schema.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        schema = json.loads((ROOT / 'src/main/resources/assets/formacraft/fbd/program.schema.json').read_text(encoding='utf-8'))
        self.assertEqual(module.build_schema(), schema)

    def test_schema_failure_never_falls_back_to_a_different_profile_building(self):
        from app.models.building_profile import BuildingProfile
        from app.services.llm_plan_repair import repair_llm_plan_validation
        p = self.plan()
        p['components'][0]['params']['assembly'] = {'ops': []}
        result = repair_llm_plan_validation(p, None, user_text='', building_profile=BuildingProfile(query='hall'),
                                            normalize_fn=lambda value, _: value)
        self.assertEqual('capability_gap', result['plan_status'])
        self.assertIn('fbd_program', result['components'][0]['params'])

    def test_remote_repair_cannot_remove_procedural_authority(self):
        from app.services.llm_plan_repair import repair_llm_plan_validation
        p = self.plan()
        p.pop('anchor')
        replacement = self.plan()
        replacement['components'][0]['params'].pop('fbd_program')
        response = SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=json.dumps(replacement)))])
        with patch('app.models.llm_plan_json_schema.call_chat_with_llm_plan_response_formats', return_value=(response, 'json_object')) as remote:
            result = repair_llm_plan_validation(p, None, user_text='', building_profile=None,
                normalize_fn=lambda value, _: value, client=Mock(), model='test', messages=[{'role': 'user', 'content': 'test'}],
                call_with_timeout=Mock())
        self.assertEqual(2, remote.call_count)
        self.assertEqual('capability_gap', result['plan_status'])
        self.assertIn('preserve procedural authority', result['error'])
