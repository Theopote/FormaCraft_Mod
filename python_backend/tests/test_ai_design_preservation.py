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


def _mass_only_brutalist() -> dict:
    return {
        "mode": "build",
        "style_profile": "Brutalist",
        "components": [
            {
                "component_type": "MASS_MAIN",
                "slot_id": "main",
                "relative_position": {"x": 0, "y": 0, "z": 0},
                "dimensions": {"width": 18, "depth": 18, "height": 22},
                "features": ["monolith"],
                "params": {"component_id": "mono", "wall_block": "minecraft:stone"},
            },
        ],
    }


def _open_structure_plan() -> dict:
    return {
        "mode": "build",
        "style_profile": "Parametric",
        "components": [
            {
                "component_type": "STRUCTURE",
                "slot_id": "main",
                "relative_position": {"x": 0, "y": 0, "z": 0},
                "dimensions": {"width": 40, "depth": 40, "height": 16},
                "features": ["freeform_shell"],
                "params": {"component_id": "shell", "generation_authority": "STRUCTURE"},
            },
        ],
    }


class AiDesignPreservationTest(unittest.TestCase):
    def test_three_divergent_compositions_survive_normalization(self):
        profile = BuildingProfile(
            query="开放式展馆",
            structure=ProfileStructure(distinguishing_features=["glass dome"]),
        )
        for plan in (_unique_plan(), _mass_only_brutalist(), _open_structure_plan()):
            with self.subTest(types=[c["component_type"] for c in plan["components"]]):
                before = copy.deepcopy(plan)
                out = _normalize_llm_plan_output(copy.deepcopy(plan), req=None, building_profile=profile)
                self.assertEqual(
                    [c["component_type"] for c in out["components"]],
                    [c["component_type"] for c in before["components"]],
                )
                for b, a in zip(before["components"], out["components"]):
                    self.assertEqual(b["dimensions"], a["dimensions"])
                    self.assertEqual(b["relative_position"], a["relative_position"])

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

    def test_ai_planned_xieshan_survives_without_user_roof_literal(self):
        from unittest.mock import Mock

        profile = BuildingProfile(
            query="宏伟的传统中式大殿",
            structure=ProfileStructure(distinguishing_features=["xieshan roof"]),
        )
        plan = {
            "mode": "build",
            "style_profile": "Chinese_Classical",
            "genome": {"culturalStyle": {"region": "chinese"}},
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "main",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 24, "depth": 16, "height": 10},
                    "features": [],
                    "params": {
                        "component_id": "hall",
                        "roof_type": "xieshan",
                        "plan_type": "solid",
                    },
                },
                {
                    "component_type": "ROOF",
                    "slot_id": "main",
                    "relative_position": {"x": 0, "y": 10, "z": 0},
                    "dimensions": {"width": 26, "depth": 18, "height": 6},
                    "features": [],
                    "params": {"component_id": "roof_a", "host_id": "hall", "roof_type": "xieshan"},
                },
            ],
        }
        req = Mock()
        req.userMessage = "建造一座宏伟的传统中式大殿"
        req.selection = None
        req.brushSelection = None
        out = _normalize_llm_plan_output(copy.deepcopy(plan), req=req, building_profile=profile)
        mass = next(c for c in out["components"] if c["component_type"] == "MASS_MAIN")
        roof = next(c for c in out["components"] if c["component_type"] == "ROOF")
        self.assertEqual("xieshan", mass["params"]["roof_type"])
        self.assertEqual("xieshan", roof["params"]["roof_type"])
        self.assertEqual("AI_PLANNED", mass["params"]["design_sources"]["roof_type"])
        self.assertEqual("AI_PLANNED", roof["params"]["design_sources"]["roof_type"])

    def test_ai_planned_courtyard_preserved_without_user_courtyard_literal(self):
        from unittest.mock import Mock

        profile = BuildingProfile(
            query="中式宅院",
            structure=ProfileStructure(distinguishing_features=["courtyard"]),
        )
        plan = {
            "mode": "build",
            "style_profile": "Chinese_Hui",
            "genome": {"culturalStyle": {"region": "chinese"}},
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "main",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 28, "depth": 22, "height": 8},
                    "features": [],
                    "params": {
                        "component_id": "house",
                        "plan_type": "courtyard",
                        "courtyard_ratio": 0.35,
                        "void_ratio": 0.3,
                        "roof_type": "xieshan",
                    },
                },
                {
                    "component_type": "COURTYARD",
                    "slot_id": "main",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 10, "depth": 10, "height": 1},
                    "features": [],
                    "params": {"component_id": "yard", "host_id": "house"},
                },
            ],
        }
        req = Mock()
        req.userMessage = "建造一座中式宅院"
        req.selection = None
        req.brushSelection = None
        out = _normalize_llm_plan_output(copy.deepcopy(plan), req=req, building_profile=profile)
        types = [c["component_type"] for c in out["components"]]
        self.assertIn("COURTYARD", types)
        mass = next(c for c in out["components"] if c["component_type"] == "MASS_MAIN")
        self.assertEqual("courtyard", mass["params"]["plan_type"])
        self.assertEqual(0.35, mass["params"]["courtyard_ratio"])

    def test_empty_chinese_roof_gets_technical_default_only(self):
        from unittest.mock import Mock

        profile = BuildingProfile(query="中式大殿", structure=ProfileStructure())
        plan = {
            "mode": "build",
            "style_profile": "Chinese_Classical",
            "genome": {"culturalStyle": {"region": "chinese"}},
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "main",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 20, "depth": 14, "height": 9},
                    "features": [],
                    "params": {"component_id": "hall"},
                },
            ],
        }
        req = Mock()
        req.userMessage = "建造一座中式大殿"
        req.selection = None
        req.brushSelection = None
        out = _normalize_llm_plan_output(copy.deepcopy(plan), req=req, building_profile=profile)
        mass = next(c for c in out["components"] if c["component_type"] == "MASS_MAIN")
        self.assertEqual("xuanshan", mass["params"]["roof_type"])
        self.assertEqual(
            "TECHNICAL_DEFAULT",
            mass["params"]["design_sources"]["roof_type"],
        )

    def test_ai_planned_roof_gets_design_source_without_downgrade(self):
        from unittest.mock import Mock

        profile = BuildingProfile(query="中式大殿", structure=ProfileStructure())
        plan = {
            "mode": "build",
            "style_profile": "Chinese_Classical",
            "genome": {"culturalStyle": {"region": "chinese"}},
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "main",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 20, "depth": 14, "height": 9},
                    "features": [],
                    "params": {
                        "component_id": "hall",
                        "roof_type": "xieshan",
                        "design_sources": {"roof_type": "USER_EXPLICIT"},
                    },
                },
            ],
        }
        req = Mock()
        req.userMessage = "建造一座中式大殿"
        req.selection = None
        req.brushSelection = None
        req.requestText = req.userMessage
        out = _normalize_llm_plan_output(copy.deepcopy(plan), req=req, building_profile=profile)
        mass = next(c for c in out["components"] if c["component_type"] == "MASS_MAIN")
        self.assertEqual("xieshan", mass["params"]["roof_type"])
        self.assertEqual("USER_EXPLICIT", mass["params"]["design_sources"]["roof_type"])

    def test_user_omission_strips_satellites_and_stamps_none(self):
        from unittest.mock import Mock

        profile = BuildingProfile(query="地下神殿", structure=ProfileStructure())
        plan = {
            "mode": "build",
            "style_profile": "DEFAULT",
            "anchor": {"x": 0, "y": 40, "z": 0},
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "main",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 18, "depth": 14, "height": 8},
                    "features": [],
                    "params": {
                        "component_id": "hall",
                        "roof_type": "gable",
                        "window_style": "casement",
                        "entrance_type": "arched",
                    },
                },
                {
                    "component_type": "ROOF",
                    "slot_id": "main",
                    "relative_position": {"x": 0, "y": 8, "z": 0},
                    "dimensions": {"width": 20, "depth": 16, "height": 4},
                    "features": [],
                    "params": {"component_id": "roof_a", "host_id": "hall", "roof_type": "gable"},
                },
                {
                    "component_type": "FACADE_WINDOWS",
                    "slot_id": "main",
                    "relative_position": {"x": 0, "y": 2, "z": 0},
                    "dimensions": {"width": 18, "depth": 1, "height": 3},
                    "features": [],
                    "params": {"component_id": "win_a", "host_id": "hall"},
                },
                {
                    "component_type": "ENTRANCE",
                    "slot_id": "main",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 3, "depth": 1, "height": 3},
                    "features": [],
                    "params": {"component_id": "door_a", "host_id": "hall"},
                },
            ],
        }
        req = Mock()
        req.userMessage = "建造一座地下神殿，无屋顶，不要窗，不要门"
        req.requestText = req.userMessage
        req.selection = None
        req.brushSelection = None
        out = _normalize_llm_plan_output(copy.deepcopy(plan), req=req, building_profile=profile)
        types = [c["component_type"] for c in out["components"]]
        self.assertNotIn("ROOF", types)
        self.assertNotIn("FACADE_WINDOWS", types)
        self.assertNotIn("ENTRANCE", types)
        mass = next(c for c in out["components"] if c["component_type"] == "MASS_MAIN")
        self.assertEqual("none", mass["params"]["roof_type"])
        self.assertEqual("none", mass["params"]["window_style"])
        self.assertEqual(0, mass["params"]["window_ratio"])
        self.assertEqual("none", mass["params"]["entrance_type"])
        self.assertEqual("USER_EXPLICIT", mass["params"]["design_sources"]["roof_type"])
        self.assertEqual("USER_EXPLICIT", mass["params"]["design_sources"]["window_style"])
        self.assertEqual("USER_EXPLICIT", mass["params"]["design_sources"]["entrance_type"])
        reqs = out["plan_meta"]["design_requirements"]
        self.assertEqual("OMITTED", reqs["roof"])
        self.assertEqual("OMITTED", reqs["facade"])
        self.assertEqual("OMITTED", reqs["entrance"])
        # Omissions cover all satellite gaps → AI-owned; no transitional infer.
        self.assertFalse(out["plan_meta"]["allow_compiler_satellites"])
        self.assertEqual("ai_owned", out["plan_meta"]["compiler_satellites_policy"])

    def test_incomplete_mass_shell_does_not_auto_authorize_satellites(self):
        from unittest.mock import Mock
        from app.services.llm_plan_normalization import _ensure_compiler_satellite_policy

        plan = {
            "mode": "build",
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "main",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 12, "depth": 10, "height": 6},
                    "features": [],
                    "params": {"component_id": "body"},
                }
            ],
        }
        out = _ensure_compiler_satellite_policy(copy.deepcopy(plan))
        self.assertFalse(out["plan_meta"]["allow_compiler_satellites"])
        self.assertEqual("ai_owned", out["plan_meta"]["compiler_satellites_policy"])

        # Existing transitional opt-in must not be overwritten.
        transitional = copy.deepcopy(plan)
        transitional["plan_meta"] = {
            "allow_compiler_satellites": True,
            "compiler_satellites_policy": "transitional_fixture",
        }
        left = _ensure_compiler_satellite_policy(transitional)
        self.assertTrue(left["plan_meta"]["allow_compiler_satellites"])

        scaffold = copy.deepcopy(plan)
        scaffold["plan_meta"] = {"allow_compiler_satellites": False, "scaffolded_from_request_intent": True}
        left_scaffold = _ensure_compiler_satellite_policy(scaffold)
        self.assertFalse(left_scaffold["plan_meta"]["allow_compiler_satellites"])

        req = Mock()
        req.userMessage = "一座简单体块"
        req.requestText = req.userMessage
        req.selection = None
        req.brushSelection = None
        profile = BuildingProfile(query="简单体块", structure=ProfileStructure())
        normalized = _normalize_llm_plan_output(copy.deepcopy(plan), req=req, building_profile=profile)
        self.assertFalse(normalized["plan_meta"]["allow_compiler_satellites"])

    def test_multi_mass_roof_and_adjacent_courtyard_stay_scoped(self):
        from app.services.llm_plan_normalization import _apply_user_explicit_design_sources

        plan = {
            "mode": "build",
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "hall",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 20, "depth": 14, "height": 9},
                    "features": [],
                    "params": {
                        "component_id": "main_hall",
                        "roof_type": "xieshan",
                        "design_sources": {"roof_type": "AI_PLANNED"},
                    },
                },
                {
                    "component_type": "ROOF",
                    "slot_id": "hall",
                    "relative_position": {"x": 0, "y": 9, "z": 0},
                    "dimensions": {"width": 22, "depth": 16, "height": 5},
                    "features": [],
                    "params": {"component_id": "roof_hall", "host_id": "main_hall", "roof_type": "xieshan"},
                },
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "wing",
                    "relative_position": {"x": 24, "y": 0, "z": 0},
                    "dimensions": {"width": 10, "depth": 8, "height": 5},
                    "features": [],
                    "params": {
                        "component_id": "side_wing",
                        "roof_type": "flat",
                        "design_sources": {"roof_type": "AI_PLANNED"},
                    },
                },
                {
                    "component_type": "ROOF",
                    "slot_id": "wing",
                    "relative_position": {"x": 24, "y": 5, "z": 0},
                    "dimensions": {"width": 12, "depth": 10, "height": 2},
                    "features": [],
                    "params": {"component_id": "roof_wing", "host_id": "side_wing", "roof_type": "flat"},
                },
            ],
        }
        # Unscoped 歇山 must not flatten the wing roof or rewrite the hall.
        out = _apply_user_explicit_design_sources(copy.deepcopy(plan), "主殿采用歇山屋顶，旁边附属建筑采用平屋顶")
        hall = next(c for c in out["components"] if c["params"]["component_id"] == "main_hall")
        wing = next(c for c in out["components"] if c["params"]["component_id"] == "side_wing")
        self.assertEqual("xieshan", hall["params"]["roof_type"])
        self.assertEqual("flat", wing["params"]["roof_type"])

        # Adjacent courtyard language must not force residential mass to courtyard plan_type.
        house_plan = {
            "mode": "build",
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "house",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 14, "depth": 12, "height": 10},
                    "features": [],
                    "params": {
                        "component_id": "house_main",
                        "plan_type": "rectangle",
                        "design_sources": {"plan_type": "AI_PLANNED"},
                    },
                },
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "yard",
                    "relative_position": {"x": 20, "y": 0, "z": 0},
                    "dimensions": {"width": 12, "depth": 12, "height": 1},
                    "features": [],
                    "params": {
                        "component_id": "courtyard_pad",
                        "plan_type": "courtyard",
                        "design_sources": {"plan_type": "AI_PLANNED"},
                    },
                },
            ],
        }
        yard_out = _apply_user_explicit_design_sources(
            copy.deepcopy(house_plan),
            "建造一栋现代三层住宅，住宅旁边设置一个传统中式庭院",
        )
        house = next(c for c in yard_out["components"] if c["params"]["component_id"] == "house_main")
        yard = next(c for c in yard_out["components"] if c["params"]["component_id"] == "courtyard_pad")
        self.assertEqual("rectangle", house["params"]["plan_type"])
        self.assertEqual("AI_PLANNED", house["params"]["design_sources"]["plan_type"])
        self.assertEqual("courtyard", yard["params"]["plan_type"])

    def test_user_explicit_xieshan_stamps_provenance_and_overrides_ai(self):
        from unittest.mock import Mock

        profile = BuildingProfile(query="歇山顶大殿", structure=ProfileStructure())
        plan = {
            "mode": "build",
            "style_profile": "Chinese_Classical",
            "genome": {"culturalStyle": {"region": "chinese"}},
            "anchor": {"x": 0, "y": 64, "z": 0},
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "main",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 20, "depth": 14, "height": 9},
                    "features": [],
                    "params": {
                        "component_id": "hall",
                        "roof_type": "xuanshan",
                        "design_sources": {"roof_type": "AI_PLANNED"},
                    },
                },
                {
                    "component_type": "ROOF",
                    "slot_id": "main",
                    "relative_position": {"x": 0, "y": 9, "z": 0},
                    "dimensions": {"width": 22, "depth": 16, "height": 5},
                    "features": [],
                    "params": {
                        "component_id": "roof_a",
                        "host_id": "hall",
                        "roof_type": "xuanshan",
                    },
                },
            ],
        }
        req = Mock()
        req.userMessage = "生成一座歇山顶大殿"
        req.requestText = req.userMessage
        req.selection = None
        req.brushSelection = None
        out = _normalize_llm_plan_output(copy.deepcopy(plan), req=req, building_profile=profile)
        mass = next(c for c in out["components"] if c["component_type"] == "MASS_MAIN")
        roof = next(c for c in out["components"] if c["component_type"] == "ROOF")
        self.assertEqual("xieshan", mass["params"]["roof_type"])
        self.assertEqual("USER_EXPLICIT", mass["params"]["design_sources"]["roof_type"])
        self.assertEqual("xieshan", roof["params"]["roof_type"])
        self.assertEqual("USER_EXPLICIT", roof["params"]["design_sources"]["roof_type"])
        self.assertEqual("USER_EXPLICIT", out["design_sources"]["roof_type"])


if __name__ == "__main__":
    unittest.main()
