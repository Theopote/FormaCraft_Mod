from copy import deepcopy
import unittest

from app.services.building_contract import apply_building_contract
from app.services.plan_architectural_enrichment import enrich_llm_plan_architectural_detail


class EnrichmentIdentityTest(unittest.TestCase):
    def plan(self):
        return {"mode": "build", "style_profile": "French_Classical", "components": [
            {"component_type": "MASS_MAIN", "slot_id": "slot_a", "dimensions": {"width": 20, "depth": 14, "height": 8},
             "relative_position": {"x": 0, "y": 0, "z": 0}, "params": {"component_id": "mass_a"}},
            {"component_type": "ROOF", "slot_id": "slot_a", "dimensions": {"width": 22, "depth": 16, "height": 3},
             "relative_position": {"x": -1, "y": 8, "z": -1}, "features": ["authored_roof"],
             "params": {"component_id": "roof_a", "host_id": "mass_a", "roof_type": "flat",
                        "material": "minecraft:white_concrete", "horse_head_walls": False}},
            {"component_type": "DECOR_DETAIL", "slot_id": "slot_a", "dimensions": {"width": 1, "depth": 1, "height": 1},
             "relative_position": {"x": 0, "y": 9, "z": 0},
             "params": {"component_id": "roof_trim", "host_id": "mass_a", "attachment_host_id": "roof_a"}},
        ]}

    def test_refinement_preserves_authored_roof_and_attachment_identity(self):
        plan = self.plan()
        out = enrich_llm_plan_architectural_detail(plan, user_text="louvre museum")
        roof = next(c for c in out["components"] if c["component_type"] == "ROOF")
        self.assertEqual(roof["params"]["component_id"], "roof_a")
        self.assertEqual(roof["params"]["host_id"], "mass_a")
        self.assertEqual(roof["params"]["roof_type"], "flat")
        self.assertEqual(roof["params"]["material"], "minecraft:white_concrete")
        self.assertIs(roof["params"]["horse_head_walls"], False)
        self.assertIn("authored_roof", roof["features"])
        normalized = apply_building_contract(out, "")
        trim = next(c for c in normalized["components"] if c["params"]["component_id"] == "roof_trim")
        self.assertEqual(trim["params"]["attachment_host_id"], "roof_a")
        self.assertTrue(any(c["params"]["component_id"] == "roof_a" for c in normalized["components"]))

    def test_generated_details_have_explicit_owner_and_slot(self):
        out = enrich_llm_plan_architectural_detail(self.plan(), user_text="louvre museum")
        generated = [c for c in out["components"] if not c["params"].get("component_id")]
        self.assertTrue(generated)
        self.assertTrue(all(c["params"].get("host_id") == "mass_a" for c in generated))
        self.assertTrue(all(c.get("slot_id") == "slot_a" for c in generated))

    def test_compound_plan_is_not_resized_or_assigned_first_roof(self):
        plan = self.plan()
        second = deepcopy(plan["components"][0])
        second["params"]["component_id"] = "mass_b"
        plan["components"].insert(0, second)
        before = deepcopy(plan)
        result = enrich_llm_plan_architectural_detail(plan, user_text="louvre museum")
        self.assertEqual([c.get("dimensions") for c in result["components"]],
                         [c.get("dimensions") for c in before["components"]])
        self.assertEqual([c.get("relative_position") for c in result["components"]],
                         [c.get("relative_position") for c in before["components"]])
        self.assertEqual(result["components"][2]["params"]["component_id"], "roof_a")

    def test_multiple_roofs_and_foreign_host_are_not_guessed(self):
        plan = self.plan()
        plan["components"].append(deepcopy(plan["components"][1]))
        before = deepcopy(plan)
        self.assertEqual(enrich_llm_plan_architectural_detail(plan), before)
        plan = self.plan()
        plan["components"][1]["params"]["host_id"] = "other_mass"
        before = deepcopy(plan)
        self.assertEqual(enrich_llm_plan_architectural_detail(plan), before)

    def test_normalizer_preserves_exact_roof_target_through_enrichment(self):
        from app.models.building_profile import BuildingProfile, StyleSpec, StyleFeatureEvidence
        from app.services.ai_planner import _normalize_llm_plan_output
        profile = BuildingProfile(style_specs=[StyleSpec(requested_name="Example", scope="mass_a",
            features=[StyleFeatureEvidence(feature="flat roof", scope="roof_a", confidence=0.9,
                source_urls=["https://museum.example/reference"])])])
        result = _normalize_llm_plan_output(self.plan(), req=None, building_profile=profile)
        report = result["proportion_hints"]["style_feature_report"]["features"][0]
        self.assertEqual(report["component_id"], "roof_a")
        self.assertEqual(report["status"], "matched_existing")
