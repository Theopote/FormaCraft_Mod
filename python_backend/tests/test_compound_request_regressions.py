import json
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from app.services.building_contract import apply_building_contract, extract_requirements
from app.services.circulation_plan_normalizer import normalize_circulation_plan

FIXTURES = Path(__file__).parent / 'fixtures' / 'complex_build_retest'


class CompoundRequestRegressions(unittest.TestCase):
    def test_logged_compounds_keep_part_requirements_off_other_buildings(self):
        for i in (1, 3, 4, 5, 6):
            with self.subTest(plan=i):
                raw = json.loads((FIXTURES / f'oct17_new_{i}.json').read_text(encoding='utf-8'))
                text = raw['proportion_hints']['architecture_intent']['original_text']
                result = normalize_circulation_plan(apply_building_contract(raw, text, finalize=True))
                self.assertNotIn('capability_gap', result)
                self.assertEqual(result, normalize_circulation_plan(result))
                self.assertEqual(result, json.loads((FIXTURES / f'oct17_new_{i}.normalized.json').read_text(encoding='utf-8')))
                reqs = result['proportion_hints']['building_contract']['requirements']
                if i == 1:
                    length = next(r for r in reqs if r['property'] == 'length')
                    self.assertEqual(['mill_main'], length['target_components'])
                if i == 3:
                    self.assertTrue(all(r['status'] == 'unsupported_scope' for r in reqs if r['property'] == 'width'))
                if i == 4:
                    stair = next(c for c in result['components'] if c['params']['component_id'] == 'interior_stair')
                    self.assertEqual('STRUCTURE', stair['component_type'])
                    self.assertEqual(6, stair['params']['to']['y'])
                if i == 5:
                    floor_height = next(r for r in reqs if r['property'] == 'floor_height')
                    self.assertEqual(['dispatch_main'], floor_height['target_components'])
                if i == 6:
                    well = next(c for c in result['components'] if c['params']['component_id'] == 'courtyard_well')
                    self.assertEqual('courtyard', well['params']['host_id'])

    def test_stair_width_before_and_after_noun_does_not_constrain_buildings(self):
        for text in ('主楼二层通过宽3格的室外楼梯抵达。', '楼梯宽3格，设置上下平台。'):
            reqs = extract_requirements(text)
            width = next(r for r in reqs if r['property'] == 'width')
            self.assertEqual('element_scope', width['scope'])
            self.assertEqual('unsupported_scope', width['status'])

    def test_wrong_named_building_dimensions_still_fail(self):
        raw = json.loads((FIXTURES / 'oct17_new_1.json').read_text(encoding='utf-8'))
        text = raw['proportion_hints']['architecture_intent']['original_text']
        mill = next(c for c in raw['components'] if c['params']['component_id'] == 'mill_main')
        mill['dimensions']['width'] = 20
        result = apply_building_contract(raw, text, finalize=True)
        self.assertEqual('E_BUILDING_CONTRACT', result['capability_gap']['code'])

    def test_optional_slot_facing_does_not_replace_compound_with_profile_box(self):
        from app.services.ai_planner import _normalize_llm_plan_output
        from app.services.llm_plan_repair import repair_llm_plan_validation
        from app.models.building_profile import BuildingProfile
        raw = json.loads((FIXTURES / 'oct17_new_6.json').read_text(encoding='utf-8'))
        raw.pop('plan_status', None)
        raw.pop('capability_gap', None)
        raw['layout']['slots'][4]['facing'] = 'NONE'
        ids = {c['params']['component_id'] for c in raw['components']}
        req = SimpleNamespace(userMessage='', selection=None, brushSelection=None, outline=None, chatHistory=[])
        with patch('app.services.llm_plan_repair.build_minimal_plan_from_profile') as fallback:
            out = repair_llm_plan_validation(raw, req, user_text='', building_profile=BuildingProfile(query='compound'),
                                             normalize_fn=_normalize_llm_plan_output)
        fallback.assert_not_called()
        self.assertEqual(len(raw['layout']['slots']), len(out['layout']['slots']))
        self.assertTrue(ids.issubset({c['params']['component_id'] for c in out['components']}))
        self.assertNotIn('facing', out['layout']['slots'][4])
