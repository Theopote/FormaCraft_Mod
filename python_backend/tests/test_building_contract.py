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
    def test_scoped_opt_outs_preserve_sources_and_require_runtime_fields(self):
        text = '第一栋不要屋顶，不开窗，不需要入口；第二栋使用平屋顶。'
        reqs = extract_requirements(text)
        first = {r['property']: r['value'] for r in reqs if r['scope'] == 'building_1'}
        self.assertEqual({'roof_type': 'none', 'window_style': 'none', 'entrance_type': 'none'}, first)
        a, b = mass('a'), mass('b', 30)
        a['params'].update(requirement_scope='building_1', **first)
        b['params']['requirement_scope'] = 'building_2'
        result = apply_building_contract({'components': [b, a]}, text, finalize=True)
        self.assertNotIn('capability_gap', result)
        a['params']['window_style'] = 'stained'
        rejected = apply_building_contract({'components': [b, a]}, text, finalize=True)
        self.assertEqual('E_BUILDING_CONTRACT', rejected['capability_gap']['code'])
    def test_scoped_roof_material_and_host_inheritance_validation(self):
        first, second = mass('first'), mass('second', 30)
        first['params'].update(requirement_scope='building_1', roof_block='minecraft:deepslate_tiles')
        second['params'].update(requirement_scope='building_2', roof_block='minecraft:spruce_planks')
        roof = {'component_type': 'ROOF', 'params': {'component_id': 'roof', 'host_id': 'first'},
                'relative_position': {'x': 0, 'y': 10, 'z': 0}}
        text = '第一栋屋顶使用深板岩瓦；第二栋屋顶使用云杉木板。'
        result = apply_building_contract({'components': [second, roof, first]}, text, finalize=True)
        self.assertNotIn('capability_gap', result)
        reqs = result['proportion_hints']['building_contract']['requirements']
        self.assertEqual(['roof_block', 'roof_block'], [r['property'] for r in reqs])
        self.assertEqual(['planned', 'planned'], [r['status'] for r in reqs])
        roof['params']['roof_block'] = 'minecraft:bricks'
        rejected = apply_building_contract({'components': [second, roof, first]}, text, finalize=True)
        self.assertEqual('E_BUILDING_CONTRACT', rejected['capability_gap']['code'])
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
        result = extract_requirements('宽15格或宽20格')
        self.assertEqual('unsupported_scope', result[0]['status'])
        self.assertIsNone(result[0]['value'])

    def test_ordinal_scopes_bind_by_explicit_identity_not_array_order(self):
        first, second = mass('first'), mass('second', 30)
        first['params']['requirement_scope'] = 'building_1'
        second['params']['requirement_scope'] = 'building_2'
        second['dimensions']['width'] = 20
        text = '第一栋宽15格；第二栋宽20格。'
        result = apply_building_contract({'components': [second, first]}, text, finalize=True)
        self.assertNotIn('capability_gap', result)
        reqs = result['proportion_hints']['building_contract']['requirements']
        self.assertEqual(['building_1', 'building_2'], [r['scope'] for r in reqs])
        self.assertEqual([['first'], ['second']], [r['target_components'] for r in reqs])
        from app.services.ai_planner import _normalize_llm_plan_output
        from app.models.llm_plan import validate_llm_plan_dict
        plan = {'mode': 'build', 'style_profile': 'DEFAULT', 'anchor': {'x': 0, 'y': 64, 'z': 0},
                'components': [second, first]}
        req = SimpleNamespace(userMessage=text, chatHistory=[], selection=None,
                              brushSelection=None, outline=None)
        normalized = _normalize_llm_plan_output(plan, req)
        validate_llm_plan_dict(normalized)
        validated = apply_building_contract(normalized, text, finalize=True)
        self.assertNotIn('capability_gap', validated)
        self.assertEqual([['first'], ['second']], [r['target_components']
                         for r in validated['proportion_hints']['building_contract']['requirements']])

    def test_missing_or_duplicate_scope_binding_never_guessed(self):
        text = '第一栋宽15格，第二栋宽20格'
        for components in ([mass('a'), mass('b')],
                           [dict(mass('a'), params={'component_id': 'a', 'requirement_scope': 'building_1'}),
                            dict(mass('b'), params={'component_id': 'b', 'requirement_scope': 'building_1'})]):
            result = apply_building_contract({'components': components}, text, finalize=True)
            self.assertEqual('E_BUILDING_CONTRACT', result['capability_gap']['code'])
            self.assertTrue(any(d['code'] == 'E_REQUIREMENT_SCOPE'
                                for d in result['proportion_hints']['building_contract']['diagnostics']))

    def test_ordinal_mentions_in_navigation_do_not_define_scope(self):
        reqs = extract_requirements('两座两层住宅，每层高度5格。从第一栋的一楼进入，到第二栋再上二楼。')
        self.assertEqual({'all_main_masses'}, {r['scope'] for r in reqs})

    def test_scoped_materials_and_entrance_use_real_slot_orientation(self):
        first, second = mass('first'), mass('second')
        first['params'].update(requirement_scope='building_1', wall_block='minecraft:stone_bricks')
        second['params'].update(requirement_scope='building_2', wall_block='minecraft:bricks', floor_block='minecraft:oak_planks')
        first['slot_id'], second['slot_id'] = 'a', 'b'
        plan = {'components': [first, second], 'layout': {'slots': [{'slot_id': 'a', 'facing': 'NORTH'}, {'slot_id': 'b', 'facing': 'SOUTH'}]}}
        text = '第一栋使用石砖外墙，正门朝南。第二栋墙体使用砖块，橡木楼板，入口朝北。'
        result = apply_building_contract(plan, text, finalize=True)
        self.assertNotIn('capability_gap', result)
        second['params']['facing'] = 'SOUTH'
        plan['layout']['slots'][1]['facing'] = 'WEST'
        result = apply_building_contract(plan, text, finalize=True)
        self.assertEqual('E_BUILDING_CONTRACT', result['capability_gap']['code'])

    def test_scoped_roof_checks_each_host_independently(self):
        first, second = mass('first'), mass('second')
        first['params']['requirement_scope'] = 'building_1'
        second['params'].update(requirement_scope='building_2', roof_type='gable')
        roof = {'component_type': 'ROOF', 'params': {'host_id': 'second', 'roof_type': 'gable'}}
        result = apply_building_contract({'components': [first, second, roof]},
                                         '第一栋使用平屋顶。第二栋宽15格。', finalize=True)
        self.assertNotIn('capability_gap', result)

    def test_global_tail_returns_to_all_buildings(self):
        reqs = extract_requirements('第一栋宽15格；第二栋宽20格。两栋均每层高度5格。')
        self.assertEqual('all_main_masses', reqs[-1]['scope'])

    def test_negated_material_is_not_a_positive_requirement(self):
        reqs = extract_requirements('不要用石砖外墙，使用橡木外墙。不要宽15格。')
        self.assertEqual(['wall_block'], [r['property'] for r in reqs])
        self.assertEqual('minecraft:oak_planks', reqs[0]['value'])

    def test_real_slot_without_facing_matches_generator_default(self):
        host = mass(); host['slot_id'] = 'room'
        plan = {'components': [host], 'layout': {'slots': [{'slot_id': 'room'}]},
                'global_constraints': {'facing': 'NORTH'}}
        result = apply_building_contract(plan, '正门朝南', finalize=True)
        self.assertIn('capability_gap', result)
        result = apply_building_contract(plan, '正门朝北', finalize=True)
        self.assertNotIn('capability_gap', result)

    def test_world_directions_record_explicit_legacy_adapter(self):
        for world, encoded in [('南', 'NORTH'), ('北', 'SOUTH'), ('东', 'WEST'), ('西', 'EAST')]:
            req = extract_requirements('入口朝' + world)[0]
            self.assertEqual(encoded, req['runtime_field_value'])
            self.assertEqual('minecraft_world', req['direction_convention'])
            result = apply_building_contract({'components': [mass()], 'global_constraints': {'facing': encoded}},
                                             '入口朝' + world, finalize=True)
            self.assertNotIn('capability_gap', result)

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

    def test_explicit_host_rebases_known_translated_coordinate_frames(self):
        host = mass(); host['slot_id'] = 'a'
        roof = {'component_type': 'ROOF', 'slot_id': 'b',
                'relative_position': {'x': 2, 'y': 3, 'z': 4}, 'params': {'host_id': 'house'}}
        result = apply_building_contract({'layout': {'slots': [
            {'id': 'a', 'anchor': {'x': 10, 'y': 0, 'z': 0}},
            {'id': 'b', 'anchor': {'x': 20, 'y': 2, 'z': 0}}]}, 'components': [host, roof]}, '')
        attached = result['components'][1]
        self.assertEqual('a', attached['slot_id'])
        self.assertEqual({'x': 12, 'y': 5, 'z': 4}, attached['relative_position'])
        self.assertEqual([], result['proportion_hints']['building_contract']['diagnostics'])
        again = apply_building_contract(result, '')
        self.assertEqual(attached['relative_position'], again['components'][1]['relative_position'])

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
