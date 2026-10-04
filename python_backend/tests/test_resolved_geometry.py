import json
from pathlib import Path
import unittest
from app.services.resolved_geometry import resolve_buildings, mass_origin, multi_mass_geometry
from app.services.building_contract import apply_building_contract


class ResolvedGeometryTest(unittest.TestCase):
    def test_shared_multi_mass_parts_and_outline_preserve_l_shaped_void(self):
        path = Path(__file__).resolve().parents[2] / 'src/test/resources/regressions/multi-mass-geometry.json'
        for case in json.loads(path.read_text(encoding='utf-8')):
            with self.subTest(case=case['name']):
                geometry, expected = multi_mass_geometry(case['component']), case['expected']
                self.assertEqual(expected['part_ids'], [p['part_id'] for p in geometry['parts']])
                self.assertEqual(expected['part_origins'], [p['local_origin'] for p in geometry['parts']])
                self.assertEqual(expected['roof_ys'], [p['roof_y_local'] for p in geometry['parts']])
                self.assertEqual(expected['envelope'], geometry['envelope_local'])
                if expected['perimeter'] is None:
                    self.assertEqual('bounds_only', geometry['footprint_status'])
                    self.assertEqual([], geometry['outline_local'])
                else:
                    self.assertEqual('rectangular_union', geometry['footprint_status'])
                    self.assertEqual(expected['perimeter'], sum(e['end']-e['start'] for e in geometry['outline_local']))
                    self.assertEqual(6, len(geometry['outline_local']))
                    east_at_six = [e for e in geometry['outline_local'] if e['direction']=='EAST' and e['plane']==6]
                    self.assertEqual([{'direction':'EAST','plane':6,'start':0,'end':2}], east_at_six)

    def test_invalid_parts_keep_original_index_and_are_not_separate_buildings(self):
        body = {'component_type':'MASS_MAIN','dimensions':{'width':6,'depth':6,'height':8},
                'params':{'component_id':'root','masses':[{'dimensions':{'width':0}},
                         {'offset':{'x':6},'dimensions':{'width':4,'depth':4,'height':5}}]}}
        buildings = resolve_buildings({'components':[body]})
        self.assertEqual(1, len(buildings))
        self.assertEqual(['root','root#mass_2'], [p['part_id'] for p in buildings[0]['multi_mass']['parts']])
        result = apply_building_contract({'components':[body]}, '建造宽10格的建筑', finalize=True)
        self.assertNotIn('capability_gap', result)
        self.assertEqual('building_envelope', result['proportion_hints']['building_contract']['requirements'][0]['dimension_subject'])
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
