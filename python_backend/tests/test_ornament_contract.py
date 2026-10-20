from copy import deepcopy
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

from app.services import ornament_contract as ornaments
from app.services.building_contract import apply_building_contract
from app.services.chinese_material_contract import apply_chinese_material_contract
from app.services.gothic_building_contract import apply_gothic_building_contract


class OrnamentContractTest(unittest.TestCase):
    def test_migration_preserves_complete_preexisting_plans_and_reports(self):
        baseline = Path(__file__).parent / 'fixtures/ornament_contract_migration.json'
        cases = json.loads(baseline.read_text(encoding='utf-8'))['cases']
        self.assertEqual(len(cases), 64)
        for case in cases:
            with self.subTest(contract=case['contract'], text=case['text'], hosts=len(case['plan']['components'])):
                actual = deepcopy(case['plan'])
                apply = apply_gothic_building_contract if case['contract'] == 'gothic' else apply_chinese_material_contract
                apply(actual, case['text'])
                self.assertEqual(case['expected'], actual)
                apply(actual, case['text'])
                self.assertEqual(case['expected'], actual, 'application must be idempotent')

    def test_new_json_contract_is_discovered_by_building_pipeline(self):
        source = ornaments._ROOT
        definition = ornaments.get_ornament_contract('gothic')
        definition.update(id='additional', report={'key': 'additional_ornaments', 'schema': 'test.v1'},
                          toggles=[{'property': 'includeRoseWindow', 'positive': '设置测试玫瑰窗'}],
                          choices=[], building_requirement_bindings={})
        with TemporaryDirectory() as directory:
            target = Path(directory)
            for path in source.glob('*.json'):
                (target / path.name).write_bytes(path.read_bytes())
            (target / 'additional.json').write_text(json.dumps(definition), encoding='utf-8')
            with patch.object(ornaments, '_ROOT', target):
                original = {'components': [{'component_type': 'STRUCTURE',
                    'features': ['typology:gothic_cathedral_hall'], 'params': {'component_id': 'church'}}]}
                result = apply_building_contract(original, '设置测试玫瑰窗')
                self.assertTrue(result['components'][0]['params']['includeRoseWindow'])
                report = result['proportion_hints']['additional_ornaments']['requirements']
                self.assertEqual(report[0]['target_components'], ['church'])
                self.assertNotIn('includeRoseWindow', original['components'][0]['params'])

    def test_dispatch_skips_patch_and_collect_contracts(self):
        source = {'mode': 'patch', 'components': []}
        original = deepcopy(source)
        ornaments.apply_ornament_contracts(source, '不要双塔')
        self.assertEqual(source, original)
        with self.assertRaisesRegex(ValueError, 'atomic owner'):
            ornaments.apply_ornament_contract({}, '不要彩画', 'chinese_timber')

    def test_collect_preserves_occurrences_for_atomic_material_conflicts(self):
        definition = ornaments.get_ornament_contract('chinese_timber')
        self.assertEqual(ornaments.collect_ornament_hits('设置彩画，取消彩画，增加彩画', definition), {
            'includePainting': [(True, '设置彩画'), (False, '取消彩画'), (True, '增加彩画')]})
        plan = {'components': [{'component_type': 'STRUCTURE',
            'params': {'component_id': 'hall', 'typology_id': 'tailiang_timber_hall'}}]}
        original = deepcopy(plan['components'])
        apply_chinese_material_contract(plan, '墙体使用石砖，设置彩画，取消彩画')
        self.assertEqual(original, plan['components'])
        self.assertEqual(plan['capability_gap']['code'], 'E_CHINESE_MATERIAL_CONTRACT')

    def test_loader_rejects_bad_schema_regex_targets_and_duplicate_properties(self):
        for change in [dict(schema='unknown'), dict(target={}),
                       dict(toggles=[{'property': 'includeWindows', 'negative': '['}]),
                       dict(toggles=[{'property': 'same', 'negative': 'a'},
                                     {'property': 'same', 'positive': 'b'}]),
                       dict(toggles=['invalid']), dict(choices=['invalid']),
                       dict(building_requirement_bindings=[]), dict(report=[]), dict(target=['bad']),
                       dict(toggles=[{'property': 'includeWindows', 'positive': 'a',
                                      'positive_verification_level': 'voxel_verified'}])]:
            definition = ornaments.get_ornament_contract('gothic')
            definition.update(change)
            with self.subTest(change=change), self.assertRaises(ValueError):
                ornaments.validate_ornament_contract(definition)

    def test_nested_negation_never_becomes_a_positive_toggle_or_tower(self):
        for text in ['不要设置室内肋拱', "don't include rib vault", 'do not include rib vault']:
            plan = {'components': [{'component_type': 'STRUCTURE',
                'params': {'typology_id': 'gothic_cathedral_hall', 'component_id': 'church'}}]}
            apply_gothic_building_contract(plan, text)
            self.assertFalse(plan['components'][0]['params']['includeRibVault'])
        for text in ['不要设置折角后殿', 'do not include faceted apse']:
            plan = {'components': [{'component_type': 'STRUCTURE',
                'params': {'typology_id': 'gothic_cathedral_hall', 'component_id': 'church'}}]}
            apply_gothic_building_contract(plan, text)
            self.assertFalse(plan['components'][0]['params']['includeApse'])
        for text in ['不要设置单塔', '不要设置双塔']:
            plan = {'components': [{'component_type': 'STRUCTURE',
                'params': {'typology_id': 'gothic_cathedral_hall', 'component_id': 'church'}}]}
            apply_gothic_building_contract(plan, text)
            self.assertNotIn('towerArrangement', plan['components'][0]['params'])
        definition = ornaments.get_ornament_contract('chinese_timber')
        self.assertEqual(ornaments.collect_ornament_hits('不要设置彩画', definition), {
            'includePainting': [(False, '不要设置彩画')]})

    def test_loader_rejects_colliding_reports_and_mismatched_filenames(self):
        with TemporaryDirectory() as directory:
            target = Path(directory)
            definition = ornaments.get_ornament_contract('gothic')
            (target / 'other.json').write_text(json.dumps(definition), encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'filename'):
                ornaments._load_contracts(target)
            definition['id'] = 'other'
            (target / 'other.json').write_text(json.dumps(definition), encoding='utf-8')
            definition['id'] = 'second'
            (target / 'second.json').write_text(json.dumps(definition), encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'duplicate ornament report'):
                ornaments._load_contracts(target)

    def test_cached_definitions_cannot_be_mutated_by_a_caller(self):
        definition = ornaments.get_ornament_contract('gothic')
        definition['toggles'].clear()
        self.assertEqual(len(ornaments.get_ornament_contract('gothic')['toggles']), 10)


if __name__ == '__main__':
    unittest.main()
