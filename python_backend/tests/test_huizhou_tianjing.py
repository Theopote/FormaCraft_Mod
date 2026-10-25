import unittest
from eval.expand_p2_assets import ASSETS,FIXTURES,load
from app.models.llm_plan import validate_llm_plan_dict
from app.services.keyword_culture_retriever import retrieve
from eval.golden_eval import evaluate_plan


class HuizhouTianjingTest(unittest.TestCase):
    def test_bilingual_card_examples_and_refusal(self):
        card=load(ASSETS/'culture_cards/huizhou_tianjing.json')
        for prompt in ('建造徽派天井院落','build a huizhou tianjing compound'):
            self.assertEqual(retrieve(prompt,topK=1)['hits'][0]['id'],card['id'])
        self.assertNotIn(card['id'],[hit['id'] for hit in retrieve('不要徽派',topK=100)['hits']])
        registered={s['plan_fixture'] for s in load(FIXTURES/'scenarios.json') if s.get('ci')}
        for name in card['llmPlanExampleRefs']:
            plan=load(FIXTURES/'plans'/name)
            validate_llm_plan_dict(plan)
            self.assertFalse(evaluate_plan(plan,prompt='build a huizhou tianjing compound').hard_failures)
            self.assertEqual(plan,load(ASSETS/'llmplan_examples'/name)['plan'])
            self.assertIn('plans/'+name,registered)
            self.assertEqual(sum(c['component_type']=='MASS_MAIN' for c in plan['components']),4)
            self.assertEqual(sum(c['component_type']=='ENTRANCE' for c in plan['components']),5)
