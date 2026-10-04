import json
from pathlib import Path
import unittest
from app.services.resolved_geometry import resolve_buildings, mass_origin
from app.services.building_contract import apply_building_contract


class ResolvedGeometryTest(unittest.TestCase):
    def test_shared_java_python_geometry_cases(self):
        path = Path(__file__).resolve().parents[2] / 'src/test/resources/regressions/resolved-geometry.json'
        for case in json.loads(path.read_text(encoding='utf-8')):
            with self.subTest(case=case['name']):
                component = dict(case['component'], slot_id='site')
                plan = {'components': [component], 'layout': {'slots': [{'slot_id': 'site', 'anchor': case['slot_anchor']}]}}
                geometry, expected = resolve_buildings(plan)[0], case['expected']
                self.assertEqual(expected['origin'], geometry['local_origin'])
                self.assertEqual(expected['plan_origin'], geometry['plan_origin'])
                self.assertEqual(expected['dimensions'], geometry['dimensions'])
                self.assertEqual(expected['floor_ys'], geometry['floor_ys_local'])
                self.assertEqual(expected['roof_y'], geometry['roof_y_local'])
                self.assertEqual(expected['floor_layout_fits'], geometry['floor_layout_fits'])

    def test_floor_overflow_is_reported_without_inventing_floor_blocks(self):
        mass = {'component_type': 'MASS_MAIN', 'relative_position': {'x':0,'y':0,'z':0},
                'dimensions': {'width':9,'depth':9,'height':8}, 'params': {'floor_count':3,'floor_height':5}}
        result = apply_building_contract({'components':[mass]}, '', finalize=True)
        self.assertEqual('E_FLOOR_ENVELOPE', result['proportion_hints']['building_contract']['diagnostics'][0]['code'])
        self.assertIn('capability_gap', result)
        self.assertEqual(8, result['components'][0]['dimensions']['height'])

    def test_anchor_alias_matches_java_and_floor_metadata_is_optional(self):
        component = {'component_type':'MASS_MAIN', 'relative_position':{'x':0,'y':7,'z':0},
                     'dimensions':{'width':12,'depth':10,'height':8}, 'params':{'anchorMode':'MIN_CORNER'}}
        self.assertEqual(component['relative_position'], mass_origin(component))
        self.assertEqual([], resolve_buildings({'components':[component]})[0]['floor_ys_local'])

    def test_plate_excluded_and_bad_floor_metadata_does_not_crash(self):
        plate = {'component_type':'MASS_MAIN','dimensions':{'width':1,'depth':2,'height':1}, 'params':{'extrude_mode':'plate'}}
        self.assertEqual([], resolve_buildings({'components':[plate]}))
        body = dict(plate, params={'floor_height':'not_a_number','floor_count':'unknown'})
        self.assertEqual([], resolve_buildings({'components':[body]})[0]['floor_ys_local'])
