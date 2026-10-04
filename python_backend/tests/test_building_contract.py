import unittest
from types import SimpleNamespace

from app.services.building_contract import apply_building_contract, extract_requirements, request_text
from app.services.circulation_plan_normalizer import _host_mass
from app.services.plan_architectural_enrichment import enrich_llm_plan_architectural_detail


def mass(identity='house', x=0):
    return {'component_type': 'MASS_MAIN', 'relative_position': {'x': x, 'y': 0, 'z': 0},
            'dimensions': {'width': 15, 'depth': 13, 'height': 11},
            'params': {'component_id': identity, 'anchor_mode': 'min_corner',
                       'floor_count': 2, 'floor_height': 5, 'roof_type': 'flat'}}


class BuildingContractTest(unittest.TestCase):
    text = '建造宽15格、深13格的两层住宅，每层高度5格，使用平屋顶。暂时不要添加家具和复杂装饰。'

    def test_supported_explicit_requirements_and_source(self):
        requirements = extract_requirements(self.text)
        self.assertEqual({r['property']: r['value'] for r in requirements},
                         {'width': 15, 'depth': 13, 'floor_count': 2, 'floor_height': 5,
                          'roof_type': 'flat', 'no_complex_decor': True})
        for requirement in requirements:
            for source in requirement['source_text']:
                self.assertIn(source, self.text)

    def test_plan_verified_is_not_block_verified_and_input_unchanged(self):
        original = {'components': [mass()]}
        result = apply_building_contract(original, self.text, finalize=True)
        contract = result['proportion_hints']['building_contract']
        self.assertEqual('plan', contract['validation_stage'])
        self.assertTrue(all(r['status'] == 'planned' for r in contract['requirements']))
        self.assertNotIn('capability_gap', result)
        self.assertNotIn('proportion_hints', original)

    def test_mismatch_rejected_without_rewriting_user_or_existing_gap(self):
        wrong = mass(); wrong['dimensions']['width'] = 20
        result = apply_building_contract({'components': [wrong]}, self.text, finalize=True)
        self.assertEqual('E_BUILDING_CONTRACT', result['capability_gap']['code'])
        self.assertEqual(20, result['components'][0]['dimensions']['width'])
        result['capability_gap'] = {'code': 'E_EXISTING'}
        self.assertEqual('E_EXISTING', apply_building_contract(result, self.text, finalize=True)['capability_gap']['code'])

    def test_ambiguous_dimensions_remain_unresolved(self):
        result = extract_requirements('第一栋宽15格，第二栋宽20格')
        self.assertEqual('unsupported_scope', result[0]['status'])
        self.assertIsNone(result[0]['value'])

    def test_identity_idempotent_and_host_can_follow_satellite(self):
        satellite = {'component_type': 'ROOF', 'params': {'roof_type': 'flat'}}
        result = apply_building_contract({'components': [satellite, mass()]}, '')
        self.assertEqual('house', result['components'][0]['params']['host_id'])
        self.assertEqual(result, apply_building_contract(result, ''))

    def test_explicit_host_beats_nearest_geometry(self):
        stair = {'component_type': 'STRUCTURE', 'params': {'host_id': 'far'},
                 'relative_position': {'x': 1, 'y': 0, 'z': 1}}
        result = apply_building_contract({'components': [mass('near'), mass('far', 30), stair]}, '')
        self.assertEqual('far', _host_mass(result, result['components'][2])['params']['component_id'])
        self.assertEqual('far', result['components'][2]['params']['building_id'])

    def test_unknown_host_and_duplicate_id_rejected(self):
        result = apply_building_contract({'components': [mass(), mass(),
            {'component_type': 'ROOF', 'params': {'host_id': 'missing'}}]}, '', finalize=True)
        codes = {d['code'] for d in result['proportion_hints']['building_contract']['diagnostics']}
        self.assertEqual({'E_COMPONENT_ID_DUPLICATE', 'E_HOST_UNKNOWN'}, codes)

    def test_bridge_not_assigned_guessed_single_host(self):
        bridge = {'component_type': 'STRUCTURE', 'features': ['bridge'], 'params': {}}
        result = apply_building_contract({'components': [mass(), bridge]}, '')
        self.assertNotIn('host_id', result['components'][1]['params'])

    def test_explicit_host_cannot_cross_real_coordinate_frames(self):
        host = mass(); host['slot_id'] = 'a'
        stair = {'component_type': 'STRUCTURE', 'slot_id': 'b', 'params': {'host_id': 'house'}}
        result = apply_building_contract({'layout': {'slots': [{'id': 'a'}, {'id': 'b'}]},
                                         'components': [host, stair]}, '', finalize=True)
        self.assertIsNone(_host_mass(result, result['components'][1]))
        self.assertEqual('E_HOST_COORDINATE_FRAME', result['proportion_hints']['building_contract']['diagnostics'][0]['code'])

    def test_patch_and_program_are_not_claimed_verified(self):
        patch = {'mode': 'patch'}
        self.assertEqual(patch, apply_building_contract(patch, self.text))
        result = apply_building_contract({'components': []}, self.text)
        self.assertTrue(all(r['status'] == 'unverified' for r in result['proportion_hints']['building_contract']['requirements']))

    def test_style_answer_inherits_only_last_human_request(self):
        req = SimpleNamespace(userMessage='中式', chatHistory=['Player: ' + self.text, 'AI: 宽99格'])
        self.assertIn(self.text, request_text(req))
        self.assertNotIn('99', request_text(req))
        req.chatHistory.append('Player: 另一栋小屋')
        self.assertEqual('中式', request_text(req))

    def test_new_request_does_not_inherit_previous_dimensions(self):
        req = SimpleNamespace(userMessage='建造一个城堡', chatHistory=['Player: ' + self.text])
        self.assertEqual(req.userMessage, request_text(req))

    def test_contract_survives_existing_plan_normalization_and_schema(self):
        from app.services.ai_planner import _normalize_llm_plan_output
        from app.models.llm_plan import validate_llm_plan_dict
        raw = {'mode': 'build', 'style_profile': 'DEFAULT', 'anchor': {'x': 0, 'y': 64, 'z': 0},
               'components': [mass()]}
        req = SimpleNamespace(userMessage=self.text, chatHistory=[], selection=None,
                              brushSelection=None, outline=None)
        normalized = _normalize_llm_plan_output(raw, req)
        validate_llm_plan_dict(normalized)
        self.assertTrue(normalized['proportion_hints']['building_contract']['requirements'])
        self.assertEqual('house', normalized['components'][0]['params']['component_id'])

    def test_non_stair_explicit_request_skips_dimension_enrichment(self):
        plan = {'components': [mass()], 'style_profile': 'European_Classical'}
        self.assertEqual(plan, enrich_llm_plan_architectural_detail(plan, user_text=self.text, profile=None))


if __name__ == '__main__':
    unittest.main()
