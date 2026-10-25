import unittest
from eval.golden_eval import _invalid_facade_params
from eval.style_warning_audit import audit


class StyleWarningAuditTest(unittest.TestCase):
    def test_java_style_vocabulary_and_native_detail_are_scoped(self):
        for pattern in ('smooth','timber_frame','neon_bands'):
            self.assertEqual(_invalid_facade_params([dict(component_type='MASS_MAIN',params=dict(wall_pattern=pattern))]),[])
        native=dict(component_type='STRUCTURE',params=dict(detailLevel='refined'))
        self.assertEqual(_invalid_facade_params([native]),[])
        self.assertTrue(_invalid_facade_params([{**native,'component_type':'FACADE_WINDOWS'}]))
        self.assertTrue(_invalid_facade_params([dict(component_type='MASS_MAIN',params=dict(wall_pattern='invented_pattern'))]))

    def test_audit_retains_all_warnings_as_unresolved(self):
        report=audit()
        self.assertFalse(report['hard_failures'])
        self.assertEqual(report['warning_count'],len(report['warnings']))
        self.assertEqual(sum(report['by_category'].values()),report['warning_count'])
        self.assertTrue(report['warnings'])
        self.assertTrue(all(row['status']=='unresolved' and row['action'] for row in report['warnings']))
