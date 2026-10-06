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
        self.assertEqual(15,len(report['styles']))
        self.assertEqual(10,len(report['structural_types']))
        self.assertTrue(all(t['evidence']=='registered_interpreter' for t in report['structural_types']))
        self.assertTrue(all(s['gameplay_acceptance']=='not_assessed' for s in report['styles']))

    def test_missing_palette_is_an_error(self):
        original=AUDIT.read
        def missing_palette(path):
            return {'palettes':{}} if path.name=='palette_catalog_v1.json' else original(path)
        with patch.object(AUDIT,'read',side_effect=missing_palette):
            self.assertTrue(any('missing palette' in e for e in AUDIT.audit()['errors']))
