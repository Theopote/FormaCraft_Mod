"""global_constraints.symmetry normalization for LlmPlan validation."""

import unittest


class TestGlobalSymmetryNormalize(unittest.TestCase):
    def _normalize(self, plan: dict, req=None, profile=None) -> dict:
        from app.services.ai_planner import _normalize_llm_plan_output

        return _normalize_llm_plan_output(plan, req=req, building_profile=profile)

    def _validate(self, plan: dict) -> None:
        from app.models.llm_plan import validate_llm_plan_dict

        validate_llm_plan_dict(plan)

    def test_radial_lowercase_maps_and_validates(self):
        plan = {
            "mode": "build",
            "style_profile": "DEFAULT",
            "anchor": {"x": 0, "y": 64, "z": 0},
            "components": [],
            "global_constraints": {"symmetry": "radial", "facing": "SOUTH"},
        }
        out = self._normalize(plan)
        self.assertEqual(out["global_constraints"]["symmetry"], "RADIAL")
        self._validate(out)

    def test_bilateral_maps_to_mirror_x(self):
        plan = {
            "mode": "build",
            "style_profile": "DEFAULT",
            "anchor": {"x": 0, "y": 64, "z": 0},
            "components": [],
            "global_constraints": {"symmetry": "bilateral"},
        }
        out = self._normalize(plan)
        self.assertEqual(out["global_constraints"]["symmetry"], "MIRROR_X")
        self._validate(out)

    def test_legacy_x_z_both_aliases(self):
        for raw, expected in (("X", "MIRROR_X"), ("Z", "MIRROR_Z"), ("BOTH", "MIRROR_X")):
            plan = {
                "mode": "build",
                "style_profile": "DEFAULT",
                "anchor": {"x": 0, "y": 64, "z": 0},
                "components": [],
                "global_constraints": {"symmetry": raw},
            }
            out = self._normalize(plan)
            self.assertEqual(out["global_constraints"]["symmetry"], expected, msg=raw)

    def test_infers_from_genome_symmetry_type(self):
        plan = {
            "mode": "build",
            "style_profile": "DEFAULT",
            "anchor": {"x": 0, "y": 64, "z": 0},
            "components": [],
            "global_constraints": {"symmetry": "invalid_value"},
            "genome": {
                "genomeVersion": "1.0",
                "symmetry": {"type": "radial", "order": 4, "mirror": True},
            },
        }
        out = self._normalize(plan)
        self.assertEqual(out["global_constraints"]["symmetry"], "RADIAL")
        self._validate(out)

    def test_stadium_like_plan_from_fixture_shape(self):
        """Regression: elliptical stadium plans often copy genome radial into global_constraints."""
        plan = {
            "mode": "build",
            "style_profile": "Deconstructivism_Zaha",
            "anchor": {"x": -181, "y": 113, "z": 204},
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 80, "depth": 60, "height": 35},
                    "features": [],
                    "params": {"shape": "ellipse"},
                }
            ],
            "global_constraints": {
                "facing": "SOUTH",
                "symmetry": "radial",
                "terrain_strategy": "ADAPTIVE",
            },
            "genome": {
                "genomeVersion": "1.0",
                "symmetry": {"type": "radial", "order": None, "mirror": None},
            },
        }
        out = self._normalize(plan)
        self.assertEqual(out["global_constraints"]["symmetry"], "RADIAL")
        self._validate(out)

    def test_genome_mirror_template_pollution_coerced(self):
        plan = {
            "mode": "build",
            "style_profile": "DEFAULT",
            "anchor": {"x": 0, "y": 64, "z": 0},
            "components": [],
            "genome": {
                "genomeVersion": "1.0",
                "symmetry": {"type": "bilateral", "order": "4", "mirror": "true|false"},
            },
        }
        out = self._normalize(plan)
        self.assertIsNone(out["genome"]["symmetry"]["mirror"])
        self.assertEqual(out["genome"]["symmetry"]["order"], 4)
        self._validate(out)

    def test_layout_slots_missing_fields_repaired(self):
        plan = {
            "mode": "build",
            "style_profile": "DEFAULT",
            "anchor": {"x": 0, "y": 64, "z": 0},
            "components": [],
            "layout": {
                "skeleton_type": "COMPOUND",
                "path_based": False,
                "slots": [{"anchor": {"x": 1, "y": 0, "z": 2}}],
            },
        }
        out = self._normalize(plan)
        slots = out["layout"]["slots"]
        self.assertEqual(len(slots), 1)
        self.assertEqual(slots[0]["slot_id"], "slot_0")
        self.assertEqual(slots[0]["anchor"], {"x": 1, "y": 0, "z": 2})
        self._validate(out)

    def test_louvre_strips_wrong_landmark_module_via_profile(self):
        from unittest.mock import Mock
        from app.models.building_profile import BuildingProfile, ProfileMinecraftStrategy

        plan = {
            "mode": "build",
            "style_profile": "Modern_Stadium",
            "anchor": {"x": 0, "y": 64, "z": 0},
            "components": [
                {
                    "component_type": "MODULE",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 60, "depth": 80, "height": 28},
                    "features": ["landmark:birds_nest_stadium"],
                    "params": {"module_id": "birds_nest_stadium"},
                },
                {
                    "component_type": "MASS_MAIN",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 40, "depth": 30, "height": 12},
                    "features": [],
                    "params": {},
                },
            ],
        }
        profile = BuildingProfile(
            query="louvre",
            minecraft_strategy=ProfileMinecraftStrategy(
                landmark_module=None,
                recommended_components=["MASS_MAIN", "ROOF", "ENTRANCE"],
            ),
        )
        req = Mock()
        req.userMessage = "louvre museum"
        req.selection = None
        req.brushSelection = None
        out = self._normalize(plan, req=req, profile=profile)
        types = [c["component_type"] for c in out["components"]]
        self.assertNotIn("MODULE", types)
        self.assertIn("MASS_MAIN", types)


if __name__ == "__main__":
    unittest.main()
