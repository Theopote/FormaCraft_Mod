from copy import deepcopy
import json
from pathlib import Path
import unittest

from app.models.building_profile import BuildingProfile, ProfileMinecraftStrategy
from app.services.building_research_agent import finalize_profile_minecraft_strategy
from app.services.building_plan_stage import (research_landmark_override_block, profile_routing_summary,
    build_plan_stage_user_block, evaluate_plan_profile_alignment, alignment_hard_failures)
from app.services.typology_registry import get_typology, is_compositional_typology, list_typologies
from app.services.typology_retriever import resolve_typology_for_intent, should_suppress_landmark_module
from app.services.typology_plan_repair import repair_migrated_landmark_components

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'src/main/resources/assets/formacraft'


class CompositionalTypologyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        entries = json.loads((ASSETS / 'style_profiles/style_pack_manifest_v1.json').read_text(encoding='utf-8'))['styles']
        cls.entries = [entry for entry in entries if is_compositional_typology(entry['typology_id'])]

    def profile(self, entry, prompt=None):
        return finalize_profile_minecraft_strategy(BuildingProfile(), prompt or entry['prompts'][0])

    def plan(self, name):
        return json.loads((ROOT / 'python_backend/eval/fixtures/plans' / name).read_text(encoding='utf-8'))

    def test_catalog_has_real_compositions_without_native_interpreter_claims(self):
        declared = {definition.id for definition in list_typologies()
                    if definition.routing_policy == 'compositional_only'}
        self.assertTrue(declared)
        self.assertEqual({entry['typology_id'] for entry in self.entries}, declared)
        for entry in self.entries:
            definition = get_typology(entry['typology_id'])
            self.assertEqual(definition.routing_policy, 'compositional_only')
            self.assertFalse(definition.interpreter_id)
            self.assertFalse(definition.reference_landmarks)
            match = resolve_typology_for_intent('', culture_card_id=entry['culture_card_id'])
            self.assertEqual(match.typology_id, entry['typology_id'])
            self.assertIsNone(match.reference_landmark_id)
            self.assertTrue(should_suppress_landmark_module(match))

    def test_bilingual_research_keeps_compositional_recommendations(self):
        for entry in self.entries:
            for prompt in entry['prompts']:
                with self.subTest(prompt=prompt):
                    mc = self.profile(entry, prompt).minecraft_strategy
                    self.assertEqual(mc.structural_typology, entry['typology_id'])
                    self.assertEqual(mc.skeleton_type, 'COMPOUND')
                    self.assertIsNone(mc.landmark_module)
                    self.assertIsNone(mc.reference_landmark)
                    self.assertIn('MASS_MAIN', mc.recommended_components)
                    self.assertIn('ROOF', mc.recommended_components)
                    self.assertNotIn('STRUCTURE', mc.recommended_components)
                    self.assertNotIn('ONLY ONE STRUCTURE', mc.notes)

    def test_system_and_user_prompts_agree_on_composition(self):
        from app.services.building_research_agent import format_profile_for_prompt
        for entry in self.entries:
            profile = self.profile(entry)
            block = research_landmark_override_block(profile)
            self.assertIn('compositional_only', block)
            self.assertNotIn('ONE STRUCTURE', block)
            self.assertIn(entry['typology_id'], profile_routing_summary(profile))
            self.assertNotIn('map layers → STRUCTURE', build_plan_stage_user_block(profile, entry['prompts'][0]))
            self.assertNotIn('ONE STRUCTURE', format_profile_for_prompt(profile, compact_routing=False))

    def test_repair_preserves_all_compositional_parts_and_rejects_fake_native_plan(self):
        for entry in self.entries:
            for name in entry['plans']:
                with self.subTest(fixture=name):
                    plan = self.plan(name)
                    before = deepcopy(plan)
                    result, repaired = repair_migrated_landmark_components(plan, structural_typology=entry['typology_id'])
                    self.assertEqual(repaired, 0)
                    self.assertEqual(result, before)
                    self.assertFalse(alignment_hard_failures(evaluate_plan_profile_alignment(result, self.profile(entry))))
            plan = self.plan(entry['plans'][0])
            plan['components'] = [{'component_type': 'MODULE'}]
            repaired, _ = repair_migrated_landmark_components(plan, structural_typology=entry['typology_id'])
            self.assertNotEqual(repaired['components'][0]['component_type'], 'STRUCTURE')
            self.assertIn('plan_respects_typology_policy', alignment_hard_failures(
                evaluate_plan_profile_alignment(repaired, self.profile(entry))))

    def test_normalizer_keeps_executable_components_and_typology_hints(self):
        from app.services.ai_planner import _normalize_llm_plan_output
        for entry in self.entries:
            for name in entry['plans']:
                with self.subTest(fixture=name):
                    result = _normalize_llm_plan_output(self.plan(name), req=None, building_profile=self.profile(entry))
                    types = {c['component_type'] for c in result['components']}
                    self.assertTrue({'MASS_MAIN', 'ROOF'}.issubset(types))
                    self.assertFalse({'STRUCTURE', 'MODULE'} & types)
                    self.assertEqual(result['proportion_hints']['typology'], entry['typology_id'])

    def test_finalization_clears_stale_landmark_metadata(self):
        profile = BuildingProfile(minecraft_strategy=ProfileMinecraftStrategy(
            landmark_module='eiffel_tower', reference_landmark='eiffel_tower', recommended_components=['MODULE']))
        result = finalize_profile_minecraft_strategy(profile, 'build a scandinavian timber house')
        self.assertIsNone(result.minecraft_strategy.reference_landmark)
        self.assertIsNone(result.minecraft_strategy.landmark_module)

    def test_generic_style_card_cannot_override_an_explicit_preset(self):
        profile = BuildingProfile(minecraft_strategy=ProfileMinecraftStrategy(
            structural_typology='concrete_civic_block', recommended_components=['MASS_MAIN']))
        result = finalize_profile_minecraft_strategy(profile, 'build a brutalist megastructure')
        self.assertEqual(result.minecraft_strategy.landmark_module, 'brutalist_megastructure')
        self.assertIsNone(result.minecraft_strategy.structural_typology)
        self.assertIn('MODULE', result.minecraft_strategy.recommended_components)


if __name__ == '__main__':
    unittest.main()
