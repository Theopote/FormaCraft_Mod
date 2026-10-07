import unittest
from copy import deepcopy

from app.models.building_profile import BuildingProfile, StyleSpec, StyleFeatureEvidence
from app.services.style_feature_compiler import apply_style_feature_defaults
from app.services.building_contract import apply_building_contract


class StyleFeatureBindingTest(unittest.TestCase):
    def plan(self):
        return {"components": [
            {"component_type": "MASS_MAIN", "params": {"component_id": "mass_a", "building_id": "house_a"}},
            {"component_type": "ROOF", "params": {"component_id": "roof_a", "host_id": "mass_a", "building_id": "house_a"}},
            {"component_type": "MASS_MAIN", "params": {"component_id": "mass_b", "building_id": "house_b"}},
            {"component_type": "ROOF", "params": {"component_id": "roof_b", "host_id": "mass_b", "building_id": "house_b"}},
        ]}

    def profile(self, style_scope="house_a", feature_scope="roof_a", feature="flat roof"):
        return BuildingProfile(style_specs=[StyleSpec(requested_name="Example", scope=style_scope,
            features=[StyleFeatureEvidence(feature=feature, scope=feature_scope,
                confidence=0.9, source_urls=["https://museum.example/reference"])])])

    def report(self, result):
        return result["proportion_hints"]["style_feature_report"]["features"][0]

    def test_exact_binding_is_independent_of_component_order_and_preserves_other_building(self):
        plan = self.plan()
        plan["components"].reverse()
        before = deepcopy(plan)
        result = apply_style_feature_defaults(plan, self.profile())
        self.assertEqual(plan, before)
        roofs = {c["params"]["component_id"]: c["params"] for c in result["components"] if c["component_type"] == "ROOF"}
        self.assertEqual(roofs["roof_a"]["roof_type"], "flat")
        self.assertNotIn("roof_type", roofs["roof_b"])
        self.assertEqual(self.report(result)["parameter_verification"], "matched")

    def test_unscoped_multi_building_is_ambiguous(self):
        result = apply_style_feature_defaults(self.plan(), self.profile("building", "roof"))
        self.assertEqual(self.report(result)["status"], "ambiguous_binding")
        self.assertTrue(all("roof_type" not in c["params"] for c in result["components"]))

    def test_cross_building_scope_and_bad_host_cannot_bind(self):
        self.assertEqual(self.report(apply_style_feature_defaults(self.plan(), self.profile("house_b", "roof_a")))["status"], "missing_target")
        plan = self.plan()
        plan["components"][1]["params"]["host_id"] = "missing"
        self.assertEqual(self.report(apply_style_feature_defaults(plan, self.profile()))["status"], "unresolved_host")

    def test_user_requirement_wins_even_if_plan_parameter_is_absent(self):
        plan = self.plan()
        plan["proportion_hints"] = {"building_contract": {"requirements": [{"property": "roof_type", "value": "gable",
            "source": "user_explicit", "scope": "all_main_masses"}]}}
        result = apply_style_feature_defaults(plan, self.profile())
        self.assertEqual(self.report(result)["status"], "user_override")
        self.assertNotIn("roof_type", result["components"][1]["params"])

    def test_conflicting_evidence_never_depends_on_order(self):
        profile = self.profile()
        profile.style_specs += self.profile(feature="gable roof").style_specs
        for specs in (profile.style_specs, list(reversed(profile.style_specs))):
            result = apply_style_feature_defaults(self.plan(), profile.model_copy(update={"style_specs": specs}))
            self.assertTrue(all(r["status"] == "conflicting_defaults" for r in result["proportion_hints"]["style_feature_report"]["features"]))
            self.assertNotIn("roof_type", result["components"][1]["params"])

    def test_existing_authored_parameter_is_reported_not_overwritten(self):
        plan = self.plan()
        plan["components"][1]["params"]["roof_type"] = "gable"
        result = apply_style_feature_defaults(plan, self.profile())
        self.assertEqual(result["components"][1]["params"]["roof_type"], "gable")
        self.assertEqual(self.report(result)["parameter_verification"], "mismatch")

    def test_duplicate_identity_rejected_and_repeated_application_keeps_geometry(self):
        plan = self.plan()
        plan["components"][3]["params"]["component_id"] = "roof_a"
        self.assertEqual(self.report(apply_style_feature_defaults(plan, self.profile("house_a", "roof")))["status"], "invalid_component_identity")
        once = apply_style_feature_defaults(self.plan(), self.profile())
        twice = apply_style_feature_defaults(once, self.profile())
        self.assertEqual(once["components"], twice["components"])

    def test_real_contract_requirement_is_honored(self):
        plan = self.plan()
        for component in plan["components"]:
            component["dimensions"] = {"width": 9, "depth": 7, "height": 4}
            component["relative_position"] = {"x": 0, "y": 0, "z": 0}
        contracted = apply_building_contract(plan, "生成两栋住宅，屋顶使用双坡屋顶。")
        result = apply_style_feature_defaults(contracted, self.profile("mass_a", "roof_a"))
        self.assertEqual(self.report(result)["status"], "user_override")

    def test_final_normalizer_emits_parameter_report(self):
        from app.services.ai_planner import _normalize_llm_plan_output
        plan = self.plan()
        for component in plan["components"]:
            component["dimensions"] = {"width": 9, "depth": 7, "height": 4}
            component["relative_position"] = {"x": 0, "y": 0, "z": 0}
        result = _normalize_llm_plan_output(plan, req=None, building_profile=self.profile("mass_a", "roof_a"))
        report = result["proportion_hints"]["style_feature_report"]
        self.assertEqual(report["verification_level"], "plan_parameters_only")
        self.assertEqual(report["features"][0]["host_id"], "mass_a")
        self.assertEqual(report["features"][0]["component_id"], "roof_a")
        self.assertIn(report["features"][0]["component_id"],
                      [c["params"]["component_id"] for c in result["components"]])
