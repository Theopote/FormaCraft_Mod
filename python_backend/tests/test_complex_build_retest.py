import json
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock

from app.services.building_contract import apply_building_contract
from app.services.circulation_plan_normalizer import normalize_circulation_plan
from app.services.keyword_culture_retriever import retrieve
from app.services.building_research_agent import resolve_structural_typology_for_intent
from app.models.llm_plan_json_schema import call_chat_with_llm_plan_response_formats

FIXTURES = Path(__file__).parent / 'fixtures' / 'complex_build_retest'


class ComplexBuildRetest(unittest.TestCase):
    def test_latest_real_plans_pass_contract_with_building_scopes(self):
        for i in range(1, 7):
            with self.subTest(plan=i):
                plan = json.loads((FIXTURES / f'latest_{i}.json').read_text(encoding='utf-8'))
                text = plan['proportion_hints']['architecture_intent']['original_text']
                result = normalize_circulation_plan(apply_building_contract(plan, text, finalize=True))
                self.assertNotIn('capability_gap', result)
                self.assertEqual(result, normalize_circulation_plan(result))
                self.assertEqual(result, json.loads((FIXTURES / f'latest_{i}.normalized.json').read_text(encoding='utf-8')))

    def test_named_requirement_still_rejects_wrong_office_height(self):
        plan = json.loads((FIXTURES / 'latest_5.json').read_text(encoding='utf-8'))
        text = plan['proportion_hints']['architecture_intent']['original_text']
        for comp in plan['components']:
            if comp['params'].get('component_id') == 'office_main':
                comp['params']['floor_height'] = 3
        result = apply_building_contract(plan, text, finalize=True)
        self.assertEqual('E_BUILDING_CONTRACT', result['capability_gap']['code'])
        requirements = result['proportion_hints']['building_contract']['requirements']
        height = next(r for r in requirements if r['property'] == 'floor_height')
        self.assertEqual(['office_main'], height['target_components'])

    def test_attached_tower_does_not_route_villa_or_hotel_to_castle(self):
        for text in ('中式别墅，由一个长方体主楼和一个圆柱形塔楼组成',
                     '三层海边旅馆，右侧附带一座两层圆形观景塔，通过二层连廊相连'):
            self.assertNotEqual('radial_fortress', resolve_structural_typology_for_intent(text))
            rag = retrieve(text, topK=3, fewShotK=0)
            self.assertFalse(any(h['id'] in ('medieval_castle', 'castle_compound') for h in rag['hits']))
        self.assertEqual('radial_fortress', resolve_structural_typology_for_intent('中世纪城堡，四角有塔楼和城墙'))

    def test_logged_gothic_towers_are_valid_hosts(self):
        plan = json.loads((FIXTURES / '3.json').read_text(encoding='utf-8'))
        result = apply_building_contract(plan, '', finalize=True)
        diagnostics = result['proportion_hints']['building_contract']['diagnostics']
        self.assertFalse(any(d['code'] == 'E_HOST_UNKNOWN' for d in diagnostics), diagnostics)
        self.assertEqual(result, json.loads((FIXTURES / '3.normalized.json').read_text(encoding='utf-8')))

    def test_logged_factory_vertical_stairs_get_horizontal_runs(self):
        plan = json.loads((FIXTURES / '5.json').read_text(encoding='utf-8'))
        result = normalize_circulation_plan(plan)
        self.assertNotIn('capability_gap', result)
        for comp in result['components']:
            if comp['component_type'] != 'STRUCTURE': continue
            p = comp['params']
            self.assertGreaterEqual(abs(p['to']['x']-p['from']['x']) + abs(p['to']['z']-p['from']['z']),
                                    abs(p['to']['y']-p['from']['y']))
        self.assertEqual(result, normalize_circulation_plan(result))
        self.assertEqual(result, json.loads((FIXTURES / '5.normalized.json').read_text(encoding='utf-8')))
        self.assertEqual(plan['components'], json.loads((FIXTURES / '5.json').read_text(encoding='utf-8'))['components'])

    def test_logged_stepped_house_applies_floor_elevations_once(self):
        plan = json.loads((FIXTURES / '4.json').read_text(encoding='utf-8'))
        result = normalize_circulation_plan(plan)
        stairs = [c for c in result['components'] if c['component_type'] == 'STRUCTURE']
        self.assertEqual([0, 5, 10], [c['relative_position']['y']+c['params']['from']['y'] for c in stairs])
        self.assertEqual([5, 10, 15], [c['relative_position']['y']+c['params']['to']['y'] for c in stairs])
        self.assertEqual(result, normalize_circulation_plan(result))
        self.assertEqual(result, json.loads((FIXTURES / '4.normalized.json').read_text(encoding='utf-8')))

    def test_timeout_retries_once_and_auth_errors_do_not_retry(self):
        api = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=Mock(side_effect=[TimeoutError(), 'ok']))))
        calls = []
        def watchdog(fn, seconds):
            calls.append(seconds)
            return fn()
        response, _ = call_chat_with_llm_plan_response_formats(api, model='test', messages=[], temperature=0,
                call_with_timeout=watchdog, timeout_sec=180, prefer_json_object=True, transient_retries=1)
        self.assertEqual('ok', response)
        self.assertEqual([180, 60], calls)
        api.chat.completions.create = Mock(side_effect=ValueError('invalid credentials'))
        with self.assertRaises(ValueError):
            call_chat_with_llm_plan_response_formats(api, model='test', messages=[], temperature=0,
                    call_with_timeout=watchdog, timeout_sec=180, prefer_json_object=True, transient_retries=1)
        self.assertEqual(1, api.chat.completions.create.call_count)
