import json
import unittest
from pathlib import Path
from app.services.circulation_plan_normalizer import normalize_circulation_plan

ROOT=Path(__file__).resolve().parents[2]/'src/test/resources/regressions/ingame_20261028_0023'
class Live0023CirculationTest(unittest.TestCase):
    def source(self):
        return json.loads((ROOT/'002322.json').read_text(encoding='utf-8'))
    def test_real_towers_fit_with_height_and_stair_width_preserved(self):
        source=self.source();out=normalize_circulation_plan(source)
        self.assertNotIn('capability_gap',out)
        actual={c['params']['component_id']:c for c in out['components']}
        for host in ('left_tower_mass','right_tower_mass'):
            old=next(c for c in source['components'] if c['params']['component_id']==host)
            self.assertEqual(old['dimensions']['height'],actual[host]['dimensions']['height'])
            self.assertEqual(8,actual[host]['dimensions']['width'])
            self.assertEqual(8,actual[host]['dimensions']['depth'])
            stairs=[c for c in out['components'] if c['params'].get('host_id')==host and c['params'].get('circulation_source')=='bounded_tower_switchback']
            self.assertEqual(3,len(stairs))
            self.assertTrue(all(c['params']['width']==2 and c['params']['landing_length']==1 for c in stairs))
        expected=json.loads((ROOT/'002322_normalized.json').read_text(encoding='utf-8'))
        self.assertEqual(expected,out)
    def test_too_wide_stairs_still_fail_instead_of_cutting_walls(self):
        source=self.source()
        for c in source['components']:
            if c['params'].get('host_id') in ('left_tower_mass','right_tower_mass') and 'from' in c['params']:c['params']['width']=3
        self.assertEqual('E_STAIR_ENVELOPE_FIT',normalize_circulation_plan(source)['capability_gap']['code'])
    def test_normalization_is_idempotent(self):
        once=normalize_circulation_plan(self.source())
        self.assertEqual(once,normalize_circulation_plan(once))

    def test_explicit_footprint_prevents_automatic_expansion(self):
        source=self.source();source.setdefault('proportion_hints',{})['architecture_intent']={'original_text':'两座6×6格的塔'}
        out=normalize_circulation_plan(source)
        self.assertEqual('E_STAIR_ENVELOPE_FIT',out['capability_gap']['code'])
