import json
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from app.services.building_contract import apply_building_contract, extract_requirements
from app.services.circulation_plan_normalizer import normalize_circulation_plan

FIXTURES = Path(__file__).parent / 'fixtures' / 'complex_build_retest'


class CompoundRequestRegressions(unittest.TestCase):
    def test_oct18_night_live_plans_keep_full_buildings_and_align_bank_deck(self):
        for i in range(1,7):
            with self.subTest(plan=i):
                raw=json.loads((FIXTURES/f'oct18_night_{i}.json').read_text(encoding='utf-8'))
                result=normalize_circulation_plan(apply_building_contract(raw,raw['proportion_hints']['architecture_intent']['original_text'],finalize=True))
                self.assertNotIn('capability_gap',result)
                self.assertEqual(result,normalize_circulation_plan(result))
                self.assertEqual(result,json.loads((FIXTURES/f'oct18_night_{i}.normalized.json').read_text(encoding='utf-8')))
                if i==1:
                    bank=next(c for c in result['components'] if c['params']['component_id']=='bank_landing')
                    self.assertEqual(1,bank['relative_position']['y'])
                    self.assertEqual('terrace',bank['params']['plate_role'])
                    self.assertEqual({'width':2,'depth':5,'height':1},bank['dimensions'])

    def test_corner_floor_evidence_is_order_independent_and_keeps_explicit_anchors(self):
        from copy import deepcopy
        from app.services.bridge_plan_normalizer import normalize_hosted_connections
        raw=json.loads((FIXTURES/'oct18_evening_5.json').read_text(encoding='utf-8'))
        forward=deepcopy(raw); backward=deepcopy(raw)
        backward['components'].reverse()
        for plan in (forward,backward):
            bridge=next(c for c in plan['components'] if c['params']['component_id']=='bridge_walkway')
            bridge['params']['anchor_mode']='center'
            normalize_hosted_connections(plan)
            self.assertEqual('center',bridge['params']['anchor_mode'])
        a={c['params']['component_id']:c for c in forward['components']}
        b={c['params']['component_id']:c for c in backward['components']}
        self.assertEqual(a['dispatch_floor_2'],b['dispatch_floor_2'])
        self.assertEqual('min_corner',a['dispatch_floor_2']['params']['anchor_mode'])

    def test_missing_slot_recovery_keeps_ambiguous_and_explicit_frames(self):
        from app.services.plan_coordinate_normalizer import recover_slot_bindings
        plan={'layout':{'slots':[{'slot_id':'a','id':'slot_hall'},{'slot_id':'b','id':'hall'}]},
              'components':[{'params':{'component_id':'hall_mass'}},{'slot_id':'custom','params':{'component_id':'hall_roof'}}]}
        recover_slot_bindings(plan)
        self.assertNotIn('slot_id',plan['components'][0])
        self.assertEqual('custom',plan['components'][1]['slot_id'])

    def test_oct18_evening_live_plans_preserve_frames_and_connections(self):
        for i in range(1,7):
            with self.subTest(plan=i):
                raw=json.loads((FIXTURES/f'oct18_evening_{i}.json').read_text(encoding='utf-8'))
                result=normalize_circulation_plan(apply_building_contract(raw,raw['proportion_hints']['architecture_intent']['original_text'],finalize=True))
                self.assertNotIn('capability_gap',result)
                self.assertEqual(result,normalize_circulation_plan(result))
                self.assertEqual(result,json.loads((FIXTURES/f'oct18_evening_{i}.normalized.json').read_text(encoding='utf-8')))
                parts={c['params']['component_id']:c for c in result['components']}
                if i==1:
                    self.assertEqual('standalone_channel_crossing',parts['mill_bridge']['params']['connection_source'])
                    self.assertEqual(raw['components'][0]['dimensions'],parts['mill_mass']['dimensions'])
                if i==2:
                    self.assertLessEqual(parts['external_stair']['params']['landing_length'],2)
                if i==3:
                    self.assertEqual('reception_main',parts['stair_reception_research']['params']['host_id'])
                if i==4:
                    self.assertEqual('STRUCTURE',parts['stair_main']['component_type'])
                    self.assertEqual('minecraft:andesite_stairs',parts['stair_main']['params']['stairs'])
                    self.assertNotIn('main_hall#access_1',parts)
                if i==6:
                    self.assertTrue(all(c.get('slot_id') for c in result['components']))
                    self.assertNotEqual(parts['chapel_mass']['slot_id'],parts['residence_mass']['slot_id'])
                    self.assertEqual(parts['residence_mass']['slot_id'],parts['residence_stair']['slot_id'])

    def test_oct18_retry_live_plans_keep_the_full_compounds(self):
        for i in range(1,7):
            with self.subTest(plan=i):
                raw=json.loads((FIXTURES/f'oct18_retry_{i}.json').read_text(encoding='utf-8'))
                text=raw['proportion_hints']['architecture_intent']['original_text']
                result=normalize_circulation_plan(apply_building_contract(raw,text,finalize=True))
                self.assertNotIn('capability_gap',result)
                self.assertEqual(result,normalize_circulation_plan(result))
                self.assertEqual(result,json.loads((FIXTURES/f'oct18_retry_{i}.normalized.json').read_text(encoding='utf-8')))
                parts={c['params']['component_id']:c for c in result['components']}
                self.assertTrue({c['params']['component_id'] for c in raw['components']
                                 if c['params'].get('circulation_source')!='missing_requested_stair'}.issubset(parts))
                if i==2:
                    self.assertEqual(12,parts['external_stair']['relative_position']['x'])
                    self.assertEqual(5,parts['gallery_deck']['dimensions']['width'])
                if i==4:
                    req=next(r for r in result['proportion_hints']['building_contract']['requirements'] if r['scope']=='named_wing')
                    self.assertEqual(['townhall_wing_north','townhall_wing_south'],req['target_components'])
                    self.assertEqual('STRUCTURE',parts['townhall_main_interior_stair']['component_type'])
                    self.assertEqual(6,parts['townhall_main_interior_stair']['params']['to']['y'])
                    self.assertNotIn('townhall_main#access_1',parts)

    def test_wing_token_does_not_match_swing_buildings(self):
        raw=json.loads((FIXTURES/'oct18_retry_4.json').read_text(encoding='utf-8'))
        text=raw['proportion_hints']['architecture_intent']['original_text']
        for part in raw['components']:
            for key in ('component_id','host_id','building_id'):
                value=part['params'].get(key)
                if isinstance(value,str): part['params'][key]=value.replace('townhall_wing_','townhall_swing_')
        result=apply_building_contract(raw,text,finalize=True)
        self.assertTrue(any(d['code']=='E_REQUIREMENT_SCOPE' and d['scope']=='named_wing'
                            for d in result['proportion_hints']['building_contract']['diagnostics']))

    def test_gallery_repair_does_not_extend_a_platform_without_bound(self):
        raw=json.loads((FIXTURES/'oct18_retry_2.json').read_text(encoding='utf-8'))
        next(c for c in raw['components'] if c['params']['component_id']=='gallery_deck')['dimensions']['width']=1
        result=normalize_circulation_plan(raw)
        parts={c['params']['component_id']:c for c in result['components']}
        self.assertEqual(1,parts['gallery_deck']['dimensions']['width'])
        self.assertEqual(10,parts['external_stair']['relative_position']['x'])

    def test_oct18_live_plans_normalize_idempotently(self):
        for i in range(1,7):
            with self.subTest(plan=i):
                raw=json.loads((FIXTURES/f'oct18_{i}.json').read_text(encoding='utf-8'))
                text=raw['proportion_hints']['architecture_intent']['original_text']
                result=normalize_circulation_plan(apply_building_contract(raw,text,finalize=True))
                self.assertNotIn('capability_gap',result)
                self.assertEqual(result,normalize_circulation_plan(result))
                self.assertEqual(result,json.loads((FIXTURES/f'oct18_{i}.normalized.json').read_text(encoding='utf-8')))
                parts={c['params']['component_id']:c for c in result['components']}
                if i==1:
                    self.assertEqual(0,parts['mill_bridge']['params']['connection_level'])
                    self.assertEqual(1,parts['east_bank_landing']['relative_position']['y'])
                if i==2:
                    self.assertEqual('reverse_gallery_approach',parts['ext_stair']['params']['circulation_fit'])
                    self.assertEqual(3,parts['gallery_south']['dimensions']['width'])
                if i==3:
                    stair=parts['stair_research_tower']['params']
                    self.assertEqual('research_main',stair['host_id'])
                    self.assertEqual('tower_main',stair['target_host_id'])
                    self.assertTrue(all(isinstance(v,int) for point in (stair['from'],stair['to']) for v in point.values()))
                if i==4:
                    req=next(r for r in result['proportion_hints']['building_contract']['requirements'] if r['scope']=='named_wing')
                    self.assertEqual(['townhall_wing_left','townhall_wing_right'],req['target_components'])
                if i==6:
                    self.assertEqual('none',parts['chapel_mass']['params']['plan_type'])
                    self.assertEqual('none',parts['residence_mass']['params']['plan_type'])

    def test_compound_courtyard_module_does_not_cut_holes_in_individual_bodies(self):
        from app.services.ai_planner import _fill_component_params
        params={'shape':'rectangle'}
        _fill_component_params(params,{'component_type':'MASS_MAIN','features':[],
                                      'dimensions':{'width':23,'depth':11,'height':12}},
                               {'modules':['chapel','residence','courtyard']})
        self.assertNotIn('plan_type',params)
        explicit={'plan_type':'courtyard','courtyard_ratio':0.4}
        _fill_component_params(explicit,{'component_type':'MASS_MAIN'}, {'modules':['courtyard']})
        self.assertEqual('courtyard',explicit['plan_type'])

    def test_invalid_bridge_height_and_wrong_wing_count_remain_errors(self):
        raw=json.loads((FIXTURES/'oct18_1.json').read_text(encoding='utf-8'))
        bridge=next(c for c in raw['components'] if c['params']['component_id']=='mill_bridge')
        bridge['relative_position']['y']=3
        result=normalize_circulation_plan(raw)
        fixed=next(c for c in result['components'] if c['params']['component_id']=='mill_bridge')
        self.assertEqual(1,fixed['params']['connection_level'])
        self.assertEqual(3,fixed['relative_position']['y'])
        raw=json.loads((FIXTURES/'oct18_4.json').read_text(encoding='utf-8'))
        text=raw['proportion_hints']['architecture_intent']['original_text']
        next(c for c in raw['components'] if c['params']['component_id']=='townhall_wing_left')['params']['floor_count']=2
        self.assertEqual('E_BUILDING_CONTRACT',apply_building_contract(raw,text,finalize=True)['capability_gap']['code'])

    def test_retest_plans_normalize_and_preserve_explicit_stair_contracts(self):
        for i in range(1,7):
            with self.subTest(plan=i):
                raw = json.loads((FIXTURES / f'oct17_retest_{i}.json').read_text(encoding='utf-8'))
                text = raw['proportion_hints']['architecture_intent']['original_text']
                result = normalize_circulation_plan(apply_building_contract(raw,text,finalize=True))
                self.assertNotIn('capability_gap',result)
                self.assertEqual(result,normalize_circulation_plan(result))
                self.assertEqual(result,json.loads((FIXTURES / f'oct17_retest_{i}.normalized.json').read_text(encoding='utf-8')))
                if i == 4:
                    stair = next(c for c in result['components'] if c['params']['component_id']=='central_stair')
                    self.assertEqual('STRUCTURE',stair['component_type'])
                    self.assertEqual(6,stair['params']['to']['y'])
                if i == 5:
                    stair = next(c for c in result['components'] if c['params']['component_id']=='exterior_stair')
                    self.assertEqual(28,stair['relative_position']['x'])
    def test_named_residence_floors_are_not_inferred_on_other_bodies(self):
        from app.services.ai_planner import _normalize_llm_plan_output
        raw = {'mode':'build','style_profile':'DEFAULT','anchor':{'x':0,'y':64,'z':0},
               'components':[{'component_type':'MASS_MAIN','relative_position':{'x':0,'y':0,'z':0},
                              'dimensions':{'width':23,'depth':11,'height':12},
                              'params':{'component_id':'chapel','anchor_mode':'min_corner'}}]}
        req = SimpleNamespace(userMessage='礼拜堂高12格。一栋两层居住楼，每层高5格。',selection=None,
                              brushSelection=None,outline=None,chatHistory=[])
        result = _normalize_llm_plan_output(raw,req)
        chapel = next(c for c in result['components'] if c['params']['component_id']=='chapel')
        self.assertNotEqual(2,chapel['params'].get('floor_count'))
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
