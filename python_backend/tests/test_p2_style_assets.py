import json
from copy import deepcopy
from pathlib import Path
import unittest
from app.services.ornament_contract import apply_ornament_contracts

ROOT=Path(__file__).resolve().parents[2]


class P2StyleAssetsTest(unittest.TestCase):
    def test_earth_house_openings_rebuild_without_default_opening_drift(self):
        from eval.expand_p2_assets import add_earth_house_openings
        from app.models.llm_plan import validate_llm_plan_dict
        from eval.golden_eval import evaluate_plan
        for style in ('desert_adobe','prairie_earth'):
            for width in (13,21):
                name=f'p2_{style}_{width}.json'
                plan=json.loads((ROOT/'python_backend/eval/fixtures/plans'/name).read_text(encoding='utf-8'))
                rebuilt=deepcopy(plan);add_earth_house_openings(rebuilt)
                self.assertEqual(plan,rebuilt)
                validate_llm_plan_dict(plan)
                self.assertEqual(plan,json.loads((ROOT/'src/main/resources/assets/formacraft/llmplan_examples'/name).read_text(encoding='utf-8'))['plan'])
                self.assertEqual(len([c for c in plan['components'] if c['component_type']=='FACADE_WINDOWS']),4)
                self.assertEqual(len([c for c in plan['components'] if c['component_type']=='STAIR_SYSTEM']),1)
                self.assertFalse({'has_entrance','has_entrance_component','has_windows'}&{c.name for c in evaluate_plan(plan).soft_failures})

    def test_dark_fortress_shells_rebuild_and_satisfy_defensive_composition(self):
        from eval.expand_p2_assets import add_dark_fortress_towers
        from eval.golden_eval import evaluate_plan
        for width in (13,21):
            name=f'p2_dark_fortress_{width}.json'
            plan=json.loads((ROOT/'python_backend/eval/fixtures/plans'/name).read_text(encoding='utf-8'))
            rebuilt=deepcopy(plan);add_dark_fortress_towers(rebuilt)
            self.assertEqual(plan,rebuilt)
            example=json.loads((ROOT/'src/main/resources/assets/formacraft/llmplan_examples'/name).read_text(encoding='utf-8'))
            self.assertEqual(example['plan'],plan)
            check=next(c for c in evaluate_plan(plan,prompt='build a dark fantasy fortress').checks if c.name=='castle_has_defensive_shell')
            self.assertTrue(check.passed)

    def test_underground_variants_rebuild_and_keep_explicit_excavation(self):
        from eval.expand_dwarven_underground import underground_plan
        from app.models.llm_plan import validate_llm_plan_dict
        from app.services.keyword_culture_retriever import retrieve
        for width in (13,21):
            folder=ROOT/'python_backend/eval/fixtures/plans'
            base=json.loads((folder/f'p2_dwarven_hall_{width}.json').read_text(encoding='utf-8'))
            plan=json.loads((folder/f'dwarven_underground_{width}.json').read_text(encoding='utf-8'))
            self.assertEqual(underground_plan(base),plan)
            validate_llm_plan_dict(plan)
            self.assertEqual(plan['components'][0]['relative_position']['y'],-20)
            self.assertEqual(plan['global_constraints']['terrain_strategy'],'PRESERVE')
            self.assertTrue(any(c['params'].get('material')=='minecraft:air' for c in plan['components']))
        for prompt in ('生成一座地下矮人石殿','build an underground dwarven hall'):
            self.assertEqual(retrieve(prompt,topK=1)['hits'][0]['id'],'dwarven_underground_hall')

    def test_treehouse_access_fixtures_rebuild_without_fewshot_drift(self):
        from eval.expand_p2_assets import add_treehouse_access
        from app.models.llm_plan import validate_llm_plan_dict
        for width in (13,21):
            name=f'p2_elven_treehouse_{width}.json'
            plan=json.loads((ROOT/'python_backend/eval/fixtures/plans'/name).read_text(encoding='utf-8'))
            validate_llm_plan_dict(plan)
            rebuilt=deepcopy(plan); add_treehouse_access(rebuilt)
            self.assertEqual(rebuilt,plan)
            example=json.loads((ROOT/'src/main/resources/assets/formacraft/llmplan_examples'/name).read_text(encoding='utf-8'))
            self.assertEqual(example['plan'],plan)
            self.assertEqual(plan['global_constraints']['terrain_strategy'],'PRESERVE')
            self.assertEqual(len([c for c in plan['components'] if c['params']['component_id'].startswith('tree_access_step_')]),13)
            entrance=next(c for c in plan['components'] if c['params']['component_id']=='tree_entrance')
            self.assertEqual(entrance['params']['door_width'],3)
            self.assertEqual(entrance['params']['host_id'],'elven_treehouse')
            guards=[c for c in plan['components'] if c['params']['component_id'].startswith('tree_access_guard_')]
            self.assertEqual(len(guards),28)
            self.assertTrue(all(c['params']['material']=='minecraft:stripped_birch_log' for c in guards))

    def test_raised_examples_rebuild_without_drifting_from_fewshots(self):
        from eval.expand_p2_assets import add_raised_access
        from app.models.llm_plan import validate_llm_plan_dict
        for style in ('stilt_timber', 'water_platform'):
            for file in (ROOT/'python_backend/eval/fixtures/plans').glob(f'p2_{style}_*.json'):
                with self.subTest(fixture=file.name):
                    plan=json.loads(file.read_text(encoding='utf-8'))
                    validate_llm_plan_dict(plan)
                    rebuilt=deepcopy(plan)
                    add_raised_access(rebuilt)
                    self.assertEqual(rebuilt,plan)
                    example=json.loads((ROOT/'src/main/resources/assets/formacraft/llmplan_examples'/file.name).read_text(encoding='utf-8'))
                    self.assertEqual(example['plan'],plan)
                    self.assertEqual(plan['global_constraints']['terrain_strategy'],'PRESERVE')
                    ids={c['params']['component_id'] for c in plan['components']}
                    self.assertIn('access_walkway' if style=='water_platform' else 'access_step_0',ids)

    def plan(self):
        return json.loads((ROOT/'python_backend/eval/fixtures/plans/p2_cyberpunk_block_13.json').read_text(encoding='utf-8'))

    def test_emission_negation_and_positive_contract(self):
        for text in ('不要霓虹','关闭发光','without neon','disable emission'):
            plan=self.plan()
            apply_ornament_contracts(plan,text)
            self.assertFalse(plan['components'][0]['params']['emissive_enabled'])
        plan=self.plan(); plan['components'][0]['params']['emissive_enabled']=False
        apply_ornament_contracts(plan,'enable neon')
        self.assertTrue(plan['components'][0]['params']['emissive_enabled'])

    def test_local_style_and_multi_building_scope_are_preserved(self):
        plan=self.plan(); plan['components'][0]['params']['style_profile']='Modern_International'
        apply_ornament_contracts(plan,'不要霓虹')
        self.assertTrue(plan['components'][0]['params']['emissive_enabled'])
        plan=self.plan(); plan['components'].append(deepcopy(plan['components'][0]))
        apply_ornament_contracts(plan,'不要霓虹')
        self.assertTrue(plan['components'][0]['params']['emissive_enabled'])
        self.assertEqual('unresolved_building_scope',plan['proportion_hints']['emissive_contract']['requirements'][0]['status'])
