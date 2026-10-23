import json
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]
ASSETS=ROOT/'src/main/resources/assets/formacraft'


class TulouAssetsTest(unittest.TestCase):
    def test_bilingual_retrieval_is_generic_and_honors_style_refusal(self):
        from app.services.keyword_culture_retriever import retrieve
        from app.services.building_request_classifier import classify_building_request_local
        for prompt in ('建造客家圆形土楼','build a hakka circular tulou'):
            with self.subTest(prompt=prompt):
                rag=retrieve(prompt,topK=1)
                self.assertEqual(rag['hits'][0]['id'],'tulou_ring')
                self.assertEqual(rag['proportionCardId'],'tulou_ring')
                self.assertTrue(rag['llmPlanFewShots'])
                self.assertFalse(classify_building_request_local(prompt).is_specific_real_building)
        for text in ('要徽派，不要土楼','without tulou'):
            self.assertNotIn('tulou_ring',[h['id'] for h in retrieve(text,topK=100)['hits']])

    def test_blueprint_examples_have_dedicated_palette_and_ci_ownership(self):
        from app.models.llm_plan import validate_llm_plan_dict
        from eval.golden_eval import evaluate_plan
        from app.services.style_profile_registry import default_palette_for_style,has_palette
        card=json.loads((ASSETS/'culture_cards/tulou_ring.json').read_text(encoding='utf-8'))
        scenarios=json.loads((ROOT/'python_backend/eval/fixtures/scenarios.json').read_text(encoding='utf-8'))
        registered={s['plan_fixture'] for s in scenarios if s.get('ci')}
        palette=default_palette_for_style('Chinese_Vernacular_Tulou')
        self.assertEqual(palette,'PALETTE_TULOU_EARTH_A')
        self.assertTrue(has_palette(palette))
        for name in card['llmPlanExampleRefs']:
            plan=json.loads((ROOT/'python_backend/eval/fixtures/plans'/name).read_text(encoding='utf-8'))
            validate_llm_plan_dict(plan)
            self.assertEqual(plan,json.loads((ASSETS/'llmplan_examples'/name).read_text(encoding='utf-8'))['plan'])
            self.assertIn('plans/'+name,registered)
            self.assertEqual(plan['components'][0]['params']['paletteId'],palette)
            self.assertEqual(plan['components'][0]['params']['blueprint']['blueprint_type'],'tulou')
            self.assertFalse(evaluate_plan(plan,label=name,prompt='build a hakka circular tulou').hard_failures)
