import unittest
from app.services.building_use_audit import audit_building_use

class BuildingUseAuditTest(unittest.TestCase):
    def plan(self):
        return {'components': [
            {'component_type': 'MASS_MAIN', 'params': {'component_id': 'home', 'floor_count': 2}},
            {'component_type': 'MASS_MAIN', 'params': {'component_id': 'shop', 'floor_count': 1}},
            {'component_type': 'ENTRANCE', 'params': {'component_id': 'shop_door', 'host_id': 'shop'}},
            {'component_type': 'STRUCTURE', 'features': ['stair:straight_single_run'], 'params': {'component_id': 'home_stair', 'host_id': 'home'}}],
            'proportion_hints': {'building_use_intent': {'bindings': [
                {'status': 'bound', 'uses': ['residential'], 'target_components': ['home']},
                {'status': 'bound', 'uses': ['shop'], 'target_components': ['shop']}]}}}

    def test_audit_is_host_isolated_and_does_not_claim_geometry(self):
        plan = self.plan()
        audit_building_use(plan)
        home, shop = plan['proportion_hints']['building_use_audit']['buildings']
        self.assertEqual(home['checks']['entrance_component'], 'not_declared')
        self.assertEqual(home['stair_components'], ['home_stair'])
        self.assertEqual(home['checks']['interfloor_circulation'], 'declared_not_verified')
        self.assertEqual(shop['entrance_components'], ['shop_door'])
        self.assertEqual(shop['checks']['interfloor_circulation'], 'not_required')
        self.assertEqual(shop['checks']['interior_clearance'], 'not_assessed')
        self.assertFalse(plan['proportion_hints']['building_use_audit']['blocking'])
        original = repr(plan)
        audit_building_use(plan)
        self.assertEqual(repr(plan), original)

    def test_missing_stairs_and_duplicate_hosts_remain_visible(self):
        plan = self.plan()
        plan['components'] = plan['components'][:-1]
        audit_building_use(plan)
        self.assertEqual(plan['proportion_hints']['building_use_audit']['buildings'][0]['checks']['interfloor_circulation'], 'not_declared')
        plan['components'].append(plan['components'][0])
        audit_building_use(plan)
        self.assertEqual(plan['proportion_hints']['building_use_audit']['buildings'][0]['status'], 'ambiguous_host')
