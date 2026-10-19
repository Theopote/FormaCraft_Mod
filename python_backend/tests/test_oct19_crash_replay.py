import json
import unittest
from pathlib import Path
from app.services.building_contract import apply_building_contract
from app.services.circulation_plan_normalizer import normalize_circulation_plan

class CrashReplayTest(unittest.TestCase):
    def test_logged_plans_normalize_stably(self):
        root=Path(__file__).parent/'fixtures/complex_build_retest'
        for i in range(1,8):
            with self.subTest(plan=i):
                raw=json.loads((root/f'oct19_crash_{i}.json').read_text(encoding='utf-8'))
                text=raw.get('proportion_hints',{}).get('architecture_intent',{}).get('original_text','')
                normalized=normalize_circulation_plan(apply_building_contract(raw,text,finalize=True))
                self.assertNotIn('capability_gap',normalized)
                self.assertEqual(normalized,normalize_circulation_plan(apply_building_contract(normalized,text,finalize=True)))
                self.assertEqual(normalized,json.loads((root/f'oct19_crash_{i}.normalized.json').read_text(encoding='utf-8')))
                if i==7:self.assertEqual(3,len(normalized['components']))
