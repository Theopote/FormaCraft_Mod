import importlib.util
from pathlib import Path
from unittest.mock import patch
import unittest

SPEC=importlib.util.spec_from_file_location('capability_audit',Path(__file__).resolve().parents[2]/'tools/audit_architecture_capabilities.py')
AUDIT=importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AUDIT)

class ArchitectureCapabilityAuditTest(unittest.TestCase):
    def test_actual_registry_dependencies_resolve(self):
        report=AUDIT.audit()
        self.assertEqual([],report['errors'])
        # Cover the whole current catalog, including compositional types that
        # intentionally have no native interpreter; do not freeze old counts.
        profiles=AUDIT.read(AUDIT.ASSETS/'style_profiles/style_profile_catalog_v1.json')['profiles']
        types=AUDIT.read(AUDIT.ASSETS/'structural_typologies/structural_typologies_v1.json')['typologies']
        self.assertEqual(set(profiles), {s['id'] for s in report['styles']})
        self.assertEqual(len(profiles),len(report['styles']))
        self.assertEqual({t['id'] for t in types}, {t['id'] for t in report['structural_types']})
        self.assertEqual(len(types),len(report['structural_types']))
        for t in report['structural_types']:
            if t['routing_policy']=='typology_first':
                self.assertEqual('registered_interpreter',t['evidence'],t['id'])
            else:
                self.assertEqual('compositional_only',t['routing_policy'],t['id'])
                self.assertEqual('catalog_only',t['evidence'],t['id'])
        self.assertTrue(all(s['gameplay_acceptance']=='not_assessed' for s in report['styles']))

    def test_missing_palette_is_an_error(self):
        original=AUDIT.read
        def missing_palette(path):
            return {'palettes':{}} if path.name=='palette_catalog_v1.json' else original(path)
        with patch.object(AUDIT,'read',side_effect=missing_palette):
            self.assertTrue(any('missing palette' in e for e in AUDIT.audit()['errors']))
