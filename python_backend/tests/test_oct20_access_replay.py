import json
import unittest
from pathlib import Path
from app.services.building_contract import apply_building_contract
from app.services.circulation_plan_normalizer import normalize_circulation_plan


class AccessReplayTest(unittest.TestCase):
    def test_logged_plans_normalize(self):
        root = Path(__file__).parent / 'fixtures/complex_build_retest'
        for i in range(1,9):
            with self.subTest(plan=i):
                raw = json.loads((root/f'oct20_access_{i}.json').read_text(encoding='utf-8'))
                text = raw['proportion_hints']['architecture_intent']['original_text']
                normalized = normalize_circulation_plan(apply_building_contract(raw,text,finalize=True))
                self.assertNotIn('capability_gap',normalized)
                self.assertEqual(normalized,normalize_circulation_plan(apply_building_contract(normalized,text,finalize=True)))
                self.assertEqual(normalized,json.loads((root/f'oct20_access_{i}.normalized.json').read_text(encoding='utf-8')))
                if i == 4:
                    self.assertEqual(3,len(normalized['components']))
                    self.assertEqual(3,len(normalized['layout']['slots']))
                if i in (5,6):
                    stair = next(c for c in normalized['components'] if c.get('params',{}).get('exterior'))
                    deck = next(c for c in normalized['components'] if c['component_type']=='TERRACE')
                    self.assertLess(stair['params']['from']['y'],stair['params']['to']['y'])
                    self.assertEqual(deck['relative_position']['y'],stair['relative_position']['y']+stair['params']['to']['y'])
