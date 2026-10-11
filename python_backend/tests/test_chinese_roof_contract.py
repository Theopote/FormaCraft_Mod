from copy import deepcopy
import unittest
from app.services.chinese_roof_contract import apply_chinese_roof_contract
from app.services.building_contract import apply_building_contract


def load_tests(loader, tests, pattern):
    return unittest.TestSuite(unittest.FunctionTestCase(value) for name, value in globals().items()
                              if name.startswith('test_'))


def hall():
    return {'components': [{'component_type': 'STRUCTURE', 'features': ['typology:tailiang_timber_hall'],
                            'params': {'component_id': 'hall', 'roofType': 'wudian'}}]}


def test_explicit_roof_controls_override_native_params_through_building_contract():
    out = apply_building_contract(hall(), '建造中式大殿，采用歇山顶，屋顶升高12格，出檐2格，坡面曲率60，翘角3格。', finalize=True)
    params = out['components'][0]['params']
    assert {k: params[k] for k in ('roofType', 'roofRise', 'roofOverhang', 'roofCurve', 'roofCornerLift')} == {
        'roofType': 'xie_shan', 'roofRise': 12, 'roofOverhang': 2, 'roofCurve': 60, 'roofCornerLift': 3}
    assert 'capability_gap' not in out


def test_opt_outs_and_negated_forms_do_not_get_ignored():
    plan = hall()
    plan['components'][0]['params'].update(roofCurve=70, roofCornerLift=3)
    apply_chinese_roof_contract(plan, '不要硬山，采用悬山，采用直坡，不要翘角。')
    assert plan['components'][0]['params']['roofType'] == 'xuan_shan'
    assert plan['components'][0]['params']['roofCurve'] == 0
    assert plan['components'][0]['params']['roofCornerLift'] == 0


def test_invalid_or_ambiguous_requests_are_atomic_and_reported():
    for text in ['采用硬山，翘角3格', '出檐0格，翘角3格', '坡面曲率120',
                 '出檐1格，出檐3格', '采用歇山又采用庑殿', '第一栋出檐2格']:
        plan = hall()
        original = deepcopy(plan['components'])
        apply_chinese_roof_contract(plan, text)
        assert plan['components'] == original
        assert plan['capability_gap']['code'] == 'E_CHINESE_ROOF_CONTRACT'
    plan = hall()
    plan['components'].append(deepcopy(plan['components'][0]))
    apply_chinese_roof_contract(plan, '出檐2格')
    assert plan['capability_gap']['code'] == 'E_CHINESE_ROOF_CONTRACT'


def test_unrelated_buildings_are_not_converted_to_halls():
    plan = {'components': [{'component_type': 'MASS_MAIN', 'params': {}}]}
    expected = deepcopy(plan)
    apply_chinese_roof_contract(plan, '出檐2格')
    assert plan == expected


def test_correction_clears_only_own_gap_and_negated_values_are_ignored():
    plan = hall()
    apply_chinese_roof_contract(plan, '出檐9格')
    assert 'capability_gap' in plan
    apply_chinese_roof_contract(plan, '出檐2格，不要屋顶升高12格')
    assert 'capability_gap' not in plan
    assert plan['components'][0]['params']['roofOverhang'] == 2
    assert 'roofRise' not in plan['components'][0]['params']
    plan['capability_gap'] = {'code': 'OTHER_ERROR'}
    apply_chinese_roof_contract(plan, '出檐2格')
    assert plan['capability_gap']['code'] == 'OTHER_ERROR'
