from copy import deepcopy
import unittest

from app.models.building_profile import BuildingProfile, StyleSpec
from app.services.hosted_style_enrichment import enrich_hosted_styles
from app.services.plan_architectural_enrichment import enrich_llm_plan_architectural_detail


class HostedStyleTest(unittest.TestCase):
    def plan(self):
        components = []
        for i, style in enumerate(("Modern_International", "Chinese_Vernacular_Huizhou")):
            components.extend([
                {"component_type": "MASS_MAIN", "params": {"component_id": f"mass_{i}", "style_profile": style},
                 "dimensions": {"width": 11, "depth": 9, "height": 5}, "relative_position": {"x": i * 30, "y": i * 8, "z": 4}},
                {"component_type": "ROOF", "params": {"component_id": f"roof_{i}", "host_id": f"mass_{i}"},
                 "dimensions": {"width": 13, "depth": 11, "height": 3}, "relative_position": {"x": i * 30, "y": 5 + i * 8, "z": 4}},
            ])
        return {"mode": "build", "components": components}

    def test_mixed_styles_apply_per_host_without_geometry_or_input_changes(self):
        plan = self.plan()
        before = deepcopy(plan)
        result = enrich_llm_plan_architectural_detail(plan)
        self.assertEqual(plan, before)
        for a, b in zip(before["components"], result["components"]):
            self.assertEqual(a["dimensions"], b["dimensions"])
            self.assertEqual(a["relative_position"], b["relative_position"])
            self.assertEqual(a["params"]["component_id"], b["params"]["component_id"])
        self.assertEqual(result["components"][1]["params"]["roof_type"], "flat")
        self.assertNotEqual(result["components"][3]["params"].get("roof_type"), "flat")

    def test_component_order_does_not_determine_style(self):
        plan = self.plan()
        first = enrich_hosted_styles(plan)
        plan["components"].reverse()
        second = enrich_hosted_styles(plan)
        index = lambda p: {c["params"]["component_id"]: c["params"] for c in p["components"]}
        self.assertEqual(index(first), index(second))

    def test_scoped_research_does_not_spill_into_other_host(self):
        plan = self.plan()
        for c in plan["components"]:
            c["params"].pop("style_profile", None)
        profile = BuildingProfile(style_specs=[StyleSpec(requested_name="Modern_International", scope="mass_0")])
        result = enrich_hosted_styles(plan, profile)
        self.assertEqual(result["components"][1]["params"]["roof_type"], "flat")
        self.assertNotIn("roof_type", result["components"][3]["params"])

    def test_authored_and_user_scoped_roofs_win(self):
        plan = self.plan()
        plan["components"][1]["params"]["roof_type"] = "gable"
        plan["proportion_hints"] = {"building_contract": {"requirements": [
            {"property": "roof_type", "value": "none", "scope": "mass_1", "source": "user_explicit"}]}}
        result = enrich_hosted_styles(plan)
        self.assertEqual(result["components"][1]["params"]["roof_type"], "gable")
        self.assertNotIn("roof_type", result["components"][3]["params"])

    def test_inferred_host_and_connector_do_not_get_defaults(self):
        plan = self.plan()
        plan["components"][1]["params"]["host_source"] = "legacy_geometry_inference"
        plan["components"][3]["features"] = ["bridge"]
        result = enrich_hosted_styles(plan)
        self.assertTrue(all("roof_type" not in c["params"] for c in result["components"] if c["component_type"] == "ROOF"))

    def test_unknown_style_is_preserved_and_no_geometry_created(self):
        plan = self.plan()
        plan["components"][0]["params"]["style_profile"] = "Uncatalogued_Regional_Style"
        result = enrich_hosted_styles(plan)
        self.assertEqual(result["components"][0], plan["components"][0])
        self.assertEqual(result["proportion_hints"]["hosted_style_enrichment"]["hosts"][0]["status"], "unsupported_style")
        self.assertEqual(len(result["components"]), len(plan["components"]))

    def test_repeated_pass_keeps_components_stable(self):
        once = enrich_hosted_styles(self.plan())
        self.assertEqual(once["components"], enrich_hosted_styles(once)["components"])
