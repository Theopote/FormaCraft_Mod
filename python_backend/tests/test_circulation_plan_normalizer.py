import json
import unittest
from pathlib import Path
from app.services.circulation_plan_normalizer import normalize_circulation_plan, ring_ops
from app.services.assembly_plan_repair import finalize_assembly_plan_or_gap
from app.services.assembly_plan_validator import validate_assembly_plan, resolve_preset_for_intent, _assembly_payload

FIXTURES = Path(__file__).resolve().parents[2] / 'src/test/resources/regressions'


class CirculationNormalizerTest(unittest.TestCase):
    def test_logged_stairs_stay_inside_wall_and_ring_uses_shell_center(self):
        for number in (1, 4, 6):
            source = json.loads((FIXTURES / f'stair-envelope/{number}.json').read_text(encoding='utf-8'))
            result = normalize_circulation_plan(source)
            self.assertNotIn('capability_gap', result)
            self.assertEqual(result, normalize_circulation_plan(result))
            for stair in result['components']:
                if stair['component_type'] != 'STRUCTURE': continue
                from app.services.circulation_plan_normalizer import _host_mass, _mass_origin
                host = _host_mass(result, stair)
                if host is None: continue
                origin, dims = _mass_origin(host), host['dimensions']
                params, rp = stair['params'], stair['relative_position']
                a, b = params['from'], params['to']
                lateral = 'x' if a['z'] != b['z'] else 'z'
                self.assertGreaterEqual(rp[lateral]+min(a[lateral],b[lateral])-params['width']//2, origin[lateral]+1)
                extra = params['landing_length'] if params['landing_length'] > 1 else 0
                for axis, dimension in (('x','width'),('z','depth')):
                    sign = (b[axis] > a[axis]) - (b[axis] < a[axis])
                    positions = [a[axis], b[axis], b[axis]+sign*extra]
                    lo = min(positions) - (params['width']//2 if axis == lateral else 0)
                    hi = max(positions) + (params['width']-1-params['width']//2 if axis == lateral else 0)
                    self.assertGreaterEqual(rp[axis]+lo, origin[axis]+1)
                    self.assertLessEqual(rp[axis]+hi, origin[axis]+dims[dimension]-2)
        source = json.loads((FIXTURES / 'stair-envelope/2.json').read_text(encoding='utf-8'))
        result = normalize_circulation_plan(source)
        core = next(c for c in result['components'] if c['component_type'] == 'ASSEMBLY')
        self.assertEqual({'x':0,'y':0,'z':0}, core['relative_position'])
        self.assertEqual([4,4], [o['r'] for o in core['params']['assembly']['ops'] if o['op']=='CYLINDER'])
        self.assertEqual(result, normalize_circulation_plan(result))

    def test_retest_switchback_structure_is_promoted(self):
        source = json.loads((FIXTURES / 'exterior-retest/3.json').read_text(encoding='utf-8'))
        result = finalize_assembly_plan_or_gap(source, '折返楼梯')
        self.assertNotIn('capability_gap', result)
        self.assertTrue(any(c['component_type'] == 'ASSEMBLY' and
                            any(o['op'] == 'STAIR_SYSTEM' for o in c['params']['assembly']['ops'])
                            for c in result['components']))

    def load(self, name):
        return json.loads((FIXTURES / (name + '.json')).read_text(encoding='utf-8'))

    def test_logged_ring_description_becomes_floors_and_flights_not_twisted_shell(self):
        source = self.load('ring-stair-description')
        result = finalize_assembly_plan_or_gap(source, '环形楼梯')
        self.assertNotIn('plan_status', result)
        ops = result['components'][1]['params']['assembly']['ops']
        self.assertTrue(any(o['op'] == 'STAIR_SYSTEM' for o in ops))
        self.assertEqual([5,10], [o['dy'] for o in ops if o['op'] == 'PUSH_ORIGIN'])
        self.assertFalse(any(o['op'] == 'SHELL_BOX' for o in ops))
        self.assertEqual(15, result['components'][0]['dimensions']['height'])
        self.assertEqual(7, source['components'][0]['dimensions']['height'])
        self.assertEqual(result, normalize_circulation_plan(result))
        self.assertEqual([], validate_assembly_plan(result))
        self.assertEqual(self.load('ring-stair-ops'), {'ops':ops})

    def test_logged_switchback_connects_by_platform_and_gets_upper_floor(self):
        source = self.load('switchback-stair-description')
        result = finalize_assembly_plan_or_gap(source, '折返楼梯')
        self.assertEqual([], validate_assembly_plan(result))
        comp = result['components'][1]
        ops = comp['params']['assembly']['ops']
        self.assertEqual(self.load('switchback-stair-ops'), {'ops':ops})
        self.assertEqual(6, ops[-1]['to']['y'])
        self.assertEqual('plate', result['components'][-1]['params']['extrude_mode'])
        self.assertEqual(1, result['components'][-1]['dimensions']['height'])
        self.assertLessEqual(comp['relative_position']['x']+comp['dimensions']['width'], 8)
        self.assertEqual(result, normalize_circulation_plan(result))

    def test_shell_tread_height_error_is_caught_before_java(self):
        errors = validate_assembly_plan(self.load('switchback-stair-description'))
        self.assertTrue(any(e.code == 'E_INT_RANGE' and e.path.endswith('.h') for e in errors))

    def test_descriptive_nested_payload_does_not_hide_outer_preset(self):
        payload = _assembly_payload({'preset':'spiral_watchtower','assembly':{'kind':'description'}})
        self.assertEqual('spiral_watchtower', payload['preset'])
        self.assertNotIn('assembly', payload)
        self.assertIsNone(resolve_preset_for_intent('螺旋楼梯'))

    def test_bad_ring_floor_level_is_rejected(self):
        source = self.load('ring-stair-description')
        source['components'][1]['params']['presetParams']['floorLevels'] = [10,5]
        self.assertEqual('capability_gap', normalize_circulation_plan(source)['plan_status'])

    def test_canonical_ring_contract_has_no_shell_preset(self):
        ops = ring_ops({}, {'radius':6,'height':15,'floorLevels':[5,10]})
        self.assertEqual(self.load('ring-stair-ops'), {'ops':ops})


if __name__ == '__main__': unittest.main()
