import copy
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

    def test_envelope_adapt_keeps_upper_floor_connection(self):
        """Interior fit may translate the stair, but floor rise and upper plate contact must hold."""
        source = {
            'mode': 'build',
            'style_profile': 'DEFAULT',
            'anchor': {'x': 0, 'y': 64, 'z': 0},
            'plan_meta': {'allow_compiler_satellites': True},
            'components': [
                {
                    'component_type': 'MASS_MAIN',
                    'slot_id': 'hall',
                    'relative_position': {'x': 0, 'y': 0, 'z': 0},
                    'dimensions': {'width': 12, 'depth': 10, 'height': 10},
                    'features': [],
                    'params': {
                        'component_id': 'hall',
                        'anchor_mode': 'min_corner',
                        'wall_thickness': 1,
                        'floor_count': 2,
                        'floor_height': 4,
                        'hollow': True,
                    },
                },
                {
                    'component_type': 'MASS_SECONDARY',
                    'slot_id': 'hall',
                    'relative_position': {'x': 0, 'y': 4, 'z': 0},
                    'dimensions': {'width': 12, 'depth': 10, 'height': 1},
                    'features': [],
                    'params': {
                        'component_id': 'upper_floor',
                        'host_id': 'hall',
                        'extrude_mode': 'plate',
                        'anchor_mode': 'min_corner',
                        'role': 'upper_floor_plate',
                    },
                },
                {
                    'component_type': 'STRUCTURE',
                    'slot_id': 'hall',
                    # z=0 with width=3 clips the south wall — fit must translate inward.
                    'relative_position': {'x': 3, 'y': 0, 'z': 0},
                    'dimensions': {'width': 6, 'depth': 3, 'height': 6},
                    'features': ['stair:straight_single_run'],
                    'params': {
                        'component_id': 'hall_stair',
                        'host_id': 'hall',
                        'from': {'x': 0, 'y': 0, 'z': 0},
                        'to': {'x': 4, 'y': 4, 'z': 0},
                        'width': 3,
                        'landing_length': 2,
                        'clearHeight': 2,
                        'stairs': 'minecraft:oak_stairs',
                        'floor': 'minecraft:oak_planks',
                    },
                },
            ],
        }
        before = copy.deepcopy(source)
        result = normalize_circulation_plan(source)
        self.assertNotEqual('capability_gap', result.get('plan_status'))
        self.assertNotIn('capability_gap', result)
        before_stair = next(c for c in before['components'] if c['params']['component_id'] == 'hall_stair')
        stair = next(c for c in result['components'] if c['params']['component_id'] == 'hall_stair')
        plate = next(c for c in result['components'] if c['params']['component_id'] == 'upper_floor')
        self.assertNotEqual(stair['relative_position'], before_stair['relative_position'],
                            'envelope fit must move the authored stair')
        self.assertGreater(stair['relative_position']['z'], before_stair['relative_position']['z'])
        self.assertEqual(4, stair['params']['to']['y'], 'floor rise to upper storey must be preserved')
        self.assertEqual(0, stair['params']['from']['y'])
        # Top landing cells (to + landing run) must still sit on the upper floor plate.
        landing = int(stair['params'].get('landing_length') or 1)
        extra = landing if landing > 1 else 0
        rp, a, b = stair['relative_position'], stair['params']['from'], stair['params']['to']
        sx = (1 if b['x'] > a['x'] else -1) if b['x'] != a['x'] else 0
        sz = (1 if b['z'] > a['z'] else -1) if b['z'] != a['z'] else 0
        for step in range(extra + 1):
            wx = rp['x'] + b['x'] + sx * step
            wz = rp['z'] + b['z'] + sz * step
            wy = rp['y'] + b['y']
            self.assertEqual(plate['relative_position']['y'], wy)
            self.assertGreaterEqual(wx, plate['relative_position']['x'])
            self.assertLess(wx, plate['relative_position']['x'] + plate['dimensions']['width'])
            self.assertGreaterEqual(wz, plate['relative_position']['z'])
            self.assertLess(wz, plate['relative_position']['z'] + plate['dimensions']['depth'])
        self.assertEqual(result, normalize_circulation_plan(copy.deepcopy(result)))


if __name__ == '__main__': unittest.main()
