"""AI Design Preservation — normalization must not silently rewrite authored plans."""

from __future__ import annotations

import copy
import unittest

from app.models.building_profile import BuildingProfile, ProfileStructure
from app.services.llm_plan_normalization import _normalize_llm_plan_output
from app.services.plan_architectural_enrichment import enrich_llm_plan_architectural_detail


def _unique_plan() -> dict:
    return {
        "mode": "build",
        "style_profile": "Custom_Pavilion",
        "distinguishing_features": ["cantilever canopy", "glass atrium"],
        "style_attributes": {
            "decorative_elements": ["ribbon glazing"],
        },
        "components": [
            {
                "component_type": "MASS_MAIN",
                "slot_id": "main",
                "relative_position": {"x": 0, "y": 0, "z": 0},
                "dimensions": {"width": 22, "depth": 14, "height": 9},
                "features": ["cantilever", "atrium_void"],
                "params": {
                    "component_id": "mass_a",
                    "wall_block": "minecraft:white_concrete",
                    "void_ratio": 0.35,
                },
            },
            {
                "component_type": "DOME",
                "slot_id": "main",
                "relative_position": {"x": 5, "y": 9, "z": 2},
                "dimensions": {"width": 12, "depth": 10, "height": 7},
                "features": ["glass_dome"],
                "params": {
                    "component_id": "dome_a",
                    "host_id": "mass_a",
                    "material": "glass",
                },
            },
            {
                "component_type": "DECOR_DETAIL",
                "slot_id": "main",
                "relative_position": {"x": 0, "y": 4, "z": 0},
                "dimensions": {"width": 22, "depth": 1, "height": 2},
                "features": ["ribbon_glazing"],
                "params": {"component_id": "band_a", "host_id": "mass_a"},
            },
        ],
        "proportion_hints": {"height_to_width": 0.41},
    }


class AiDesignPreservationTest(unittest.TestCase):
    def test_enrichment_preserves_authored_geometry(self):
        profile = BuildingProfile(
            query="带玻璃穹顶的现代展馆",
            structure=ProfileStructure(
                distinguishing_features=["glass dome", "cantilever canopy"],
            ),
        )
        plan = _unique_plan()
        before = copy.deepcopy(plan)
        out = enrich_llm_plan_architectural_detail(
            plan,
            user_text="一座带玻璃穹顶的中式现代展馆",
            profile=profile,
        )
        self.assertEqual(
            [c["component_type"] for c in out["components"]],
            [c["component_type"] for c in before["components"]],
        )
        for before_c, after_c in zip(before["components"], out["components"]):
            self.assertEqual(before_c["dimensions"], after_c["dimensions"])
            self.assertEqual(before_c["relative_position"], after_c["relative_position"])
            self.assertEqual(before_c["features"], after_c["features"])
            self.assertEqual(before_c["params"].get("host_id"), after_c["params"].get("host_id"))

    def test_normalize_keeps_unique_composition_intact(self):
        profile = BuildingProfile(
            query="现代展馆",
            structure=ProfileStructure(
                distinguishing_features=["glass dome", "cantilever canopy"],
            ),
        )
        plan = _unique_plan()
        before = copy.deepcopy(plan)
        out = _normalize_llm_plan_output(plan, req=None, building_profile=profile)
        types = [c["component_type"] for c in out["components"]]
        self.assertIn("DOME", types)
        self.assertIn("DECOR_DETAIL", types)
        self.assertNotIn("CROWN", types)
        # Research may attach side-channel hints, but must not invent decorative_elements.
        self.assertEqual(
            out.get("style_attributes", {}).get("decorative_elements"),
            before["style_attributes"]["decorative_elements"],
        )
        dome = next(c for c in out["components"] if c["component_type"] == "DOME")
        self.assertEqual(dome["dimensions"], before["components"][1]["dimensions"])
        self.assertEqual(dome["params"]["host_id"], "mass_a")


if __name__ == "__main__":
    unittest.main()
