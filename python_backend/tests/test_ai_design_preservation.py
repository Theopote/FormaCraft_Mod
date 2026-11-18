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
        # LLM-declared USER_EXPLICIT is demoted unless player text re-authorizes the roof.
        self.assertEqual("AI_PLANNED", mass["params"]["design_sources"]["roof_type"])

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

    def test_blank_multi_mass_roofs_bind_independently_by_building_scope(self):
        from app.services.llm_plan_normalization import _apply_user_explicit_design_sources

        plan = {
            "mode": "build",
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "a",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 12, "depth": 10, "height": 8},
                    "features": [],
                    "params": {"component_id": "mass_a", "requirement_scope": "building_1"},
                },
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "b",
                    "relative_position": {"x": 20, "y": 0, "z": 0},
                    "dimensions": {"width": 10, "depth": 8, "height": 6},
                    "features": [],
                    "params": {"component_id": "mass_b", "requirement_scope": "building_2"},
                },
            ],
        }
        out = _apply_user_explicit_design_sources(
            copy.deepcopy(plan),
            "第一栋使用歇山顶；第二栋使用平屋顶。",
        )
        a = next(c for c in out["components"] if c["params"]["component_id"] == "mass_a")
        b = next(c for c in out["components"] if c["params"]["component_id"] == "mass_b")
        self.assertEqual("xieshan", a["params"]["roof_type"])
        self.assertEqual("USER_EXPLICIT", a["params"]["design_sources"]["roof_type"])
        self.assertEqual("flat", b["params"]["roof_type"])
        self.assertEqual("USER_EXPLICIT", b["params"]["design_sources"]["roof_type"])
        # Local constraints must not lock the whole plan.
        self.assertNotEqual("USER_EXPLICIT", (out.get("design_sources") or {}).get("roof_type"))

    def test_local_main_hall_roof_does_not_plan_lock_wing(self):
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
                        "requirement_scope": "building_1",
                        "roof_type": "gable",
                        "design_sources": {"roof_type": "AI_PLANNED"},
                    },
                },
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "wing",
                    "relative_position": {"x": 24, "y": 0, "z": 0},
                    "dimensions": {"width": 10, "depth": 8, "height": 5},
                    "features": [],
                    "params": {
                        "component_id": "side_wing",
                        "requirement_scope": "building_2",
                        "roof_type": "flat",
                        "design_sources": {"roof_type": "AI_PLANNED"},
                    },
                },
            ],
        }
        out = _apply_user_explicit_design_sources(
            copy.deepcopy(plan), "第一栋采用歇山顶；第二栋保持原样。"
        )
        hall = next(c for c in out["components"] if c["params"]["component_id"] == "main_hall")
        wing = next(c for c in out["components"] if c["params"]["component_id"] == "side_wing")
        self.assertEqual("xieshan", hall["params"]["roof_type"])
        self.assertEqual("USER_EXPLICIT", hall["params"]["design_sources"]["roof_type"])
        self.assertEqual("flat", wing["params"]["roof_type"])
        self.assertEqual("AI_PLANNED", wing["params"]["design_sources"]["roof_type"])
        self.assertNotEqual("USER_EXPLICIT", (out.get("design_sources") or {}).get("roof_type"))

    def test_scoped_window_and_entrance_omissions_are_independent(self):
        from app.services.llm_plan_normalization import _apply_user_explicit_design_sources

        def dual(omit_text: str):
            plan = {
                "mode": "build",
                "components": [
                    {
                        "component_type": "MASS_MAIN",
                        "slot_id": "a",
                        "relative_position": {"x": 0, "y": 0, "z": 0},
                        "dimensions": {"width": 12, "depth": 10, "height": 8},
                        "features": [],
                        "params": {
                            "component_id": "mass_a",
                            "requirement_scope": "building_1",
                            "window_style": "regular",
                            "entrance_type": "door",
                        },
                    },
                    {
                        "component_type": "FACADE_WINDOWS",
                        "slot_id": "a",
                        "relative_position": {"x": 0, "y": 1, "z": 0},
                        "dimensions": {"width": 1, "depth": 8, "height": 4},
                        "features": [],
                        "params": {"component_id": "win_a", "host_id": "mass_a", "window_style": "regular"},
                    },
                    {
                        "component_type": "ENTRANCE",
                        "slot_id": "a",
                        "relative_position": {"x": 5, "y": 0, "z": 9},
                        "dimensions": {"width": 2, "depth": 1, "height": 3},
                        "features": [],
                        "params": {"component_id": "door_a", "host_id": "mass_a", "entrance_type": "door"},
                    },
                    {
                        "component_type": "MASS_MAIN",
                        "slot_id": "b",
                        "relative_position": {"x": 20, "y": 0, "z": 0},
                        "dimensions": {"width": 10, "depth": 8, "height": 6},
                        "features": [],
                        "params": {
                            "component_id": "mass_b",
                            "requirement_scope": "building_2",
                            "window_style": "regular",
                            "entrance_type": "door",
                        },
                    },
                    {
                        "component_type": "FACADE_WINDOWS",
                        "slot_id": "b",
                        "relative_position": {"x": 20, "y": 1, "z": 0},
                        "dimensions": {"width": 1, "depth": 6, "height": 3},
                        "features": [],
                        "params": {"component_id": "win_b", "host_id": "mass_b", "window_style": "regular"},
                    },
                    {
                        "component_type": "ENTRANCE",
                        "slot_id": "b",
                        "relative_position": {"x": 24, "y": 0, "z": 7},
                        "dimensions": {"width": 2, "depth": 1, "height": 3},
                        "features": [],
                        "params": {"component_id": "door_b", "host_id": "mass_b", "entrance_type": "door"},
                    },
                ],
            }
            return _apply_user_explicit_design_sources(copy.deepcopy(plan), omit_text)

        win_out = dual("第一栋不开窗；第二栋保留窗户。")
        self.assertEqual("none", next(c for c in win_out["components"] if c["params"]["component_id"] == "mass_a")["params"]["window_style"])
        self.assertEqual("regular", next(c for c in win_out["components"] if c["params"]["component_id"] == "mass_b")["params"]["window_style"])
        self.assertFalse(any(c.get("params", {}).get("component_id") == "win_a" for c in win_out["components"]))
        self.assertTrue(any(c.get("params", {}).get("component_id") == "win_b" for c in win_out["components"]))
        self.assertNotEqual("USER_EXPLICIT", (win_out.get("design_sources") or {}).get("window_style"))

        door_out = dual("第一栋不需要入口；第二栋保留入口。")
        self.assertEqual("none", next(c for c in door_out["components"] if c["params"]["component_id"] == "mass_a")["params"]["entrance_type"])
        self.assertEqual("door", next(c for c in door_out["components"] if c["params"]["component_id"] == "mass_b")["params"]["entrance_type"])
        self.assertFalse(any(c.get("params", {}).get("component_id") == "door_a" for c in door_out["components"]))
        self.assertTrue(any(c.get("params", {}).get("component_id") == "door_b" for c in door_out["components"]))

    def test_scoped_roof_none_leaves_other_mass_roof(self):
        from app.services.llm_plan_normalization import _apply_user_explicit_design_sources

        plan = {
            "mode": "build",
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "a",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 12, "depth": 10, "height": 8},
                    "features": [],
                    "params": {"component_id": "mass_a", "requirement_scope": "building_1", "roof_type": "gable"},
                },
                {
                    "component_type": "ROOF",
                    "slot_id": "a",
                    "relative_position": {"x": 0, "y": 8, "z": 0},
                    "dimensions": {"width": 14, "depth": 12, "height": 3},
                    "features": [],
                    "params": {"component_id": "roof_a", "host_id": "mass_a", "roof_type": "gable"},
                },
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "b",
                    "relative_position": {"x": 20, "y": 0, "z": 0},
                    "dimensions": {"width": 10, "depth": 8, "height": 6},
                    "features": [],
                    "params": {"component_id": "mass_b", "requirement_scope": "building_2", "roof_type": "flat"},
                },
                {
                    "component_type": "ROOF",
                    "slot_id": "b",
                    "relative_position": {"x": 20, "y": 6, "z": 0},
                    "dimensions": {"width": 12, "depth": 10, "height": 2},
                    "features": [],
                    "params": {"component_id": "roof_b", "host_id": "mass_b", "roof_type": "flat"},
                },
            ],
        }
        out = _apply_user_explicit_design_sources(
            copy.deepcopy(plan), "第一栋不要屋顶；第二栋使用平屋顶。"
        )
        self.assertEqual("none", next(c for c in out["components"] if c["params"]["component_id"] == "mass_a")["params"]["roof_type"])
        self.assertFalse(any(c.get("params", {}).get("component_id") == "roof_a" for c in out["components"]))
        self.assertEqual("flat", next(c for c in out["components"] if c["params"]["component_id"] == "mass_b")["params"]["roof_type"])
        self.assertTrue(any(c.get("params", {}).get("component_id") == "roof_b" for c in out["components"]))
        self.assertNotEqual("OMITTED", ((out.get("plan_meta") or {}).get("design_requirements") or {}).get("roof"))

    def test_unbound_roofs_are_not_rewritten_from_local_mass_constraint(self):
        from app.services.llm_plan_normalization import _apply_user_explicit_design_sources

        plan = {
            "mode": "build",
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "a",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 12, "depth": 10, "height": 8},
                    "features": [],
                    "params": {"component_id": "mass_a", "requirement_scope": "building_1"},
                },
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "b",
                    "relative_position": {"x": 20, "y": 0, "z": 0},
                    "dimensions": {"width": 10, "depth": 8, "height": 6},
                    "features": [],
                    "params": {"component_id": "mass_b", "requirement_scope": "building_2"},
                },
                {
                    "component_type": "ROOF",
                    "slot_id": "a",
                    "relative_position": {"x": 0, "y": 8, "z": 0},
                    "dimensions": {"width": 14, "depth": 12, "height": 3},
                    "features": [],
                    "params": {"component_id": "roof_orphan_1", "roof_type": "gable"},
                },
                {
                    "component_type": "ROOF",
                    "slot_id": "b",
                    "relative_position": {"x": 20, "y": 6, "z": 0},
                    "dimensions": {"width": 12, "depth": 10, "height": 2},
                    "features": [],
                    "params": {"component_id": "roof_orphan_2", "roof_type": "hip"},
                },
            ],
        }
        out = _apply_user_explicit_design_sources(
            copy.deepcopy(plan), "第一栋使用歇山顶；第二栋使用平屋顶。"
        )
        r1 = next(c for c in out["components"] if c["params"]["component_id"] == "roof_orphan_1")
        r2 = next(c for c in out["components"] if c["params"]["component_id"] == "roof_orphan_2")
        self.assertEqual("gable", r1["params"]["roof_type"])
        self.assertEqual("hip", r2["params"]["roof_type"])
        self.assertNotIn("design_sources", r1["params"])
        self.assertNotIn("design_sources", r2["params"])

    def test_all_main_masses_xieshan_overwrites_existing_ai_roofs(self):
        from app.services.llm_plan_normalization import _apply_user_explicit_design_sources

        plan = {
            "mode": "build",
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "a",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 12, "depth": 10, "height": 8},
                    "features": [],
                    "params": {
                        "component_id": "main_house",
                        "roof_type": "gable",
                        "design_sources": {"roof_type": "AI_PLANNED"},
                    },
                },
                {
                    "component_type": "ROOF",
                    "slot_id": "a",
                    "relative_position": {"x": 0, "y": 8, "z": 0},
                    "dimensions": {"width": 14, "depth": 12, "height": 3},
                    "features": [],
                    "params": {"component_id": "roof_a", "host_id": "main_house", "roof_type": "gable"},
                },
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "b",
                    "relative_position": {"x": 20, "y": 0, "z": 0},
                    "dimensions": {"width": 10, "depth": 8, "height": 6},
                    "features": [],
                    "params": {
                        "component_id": "annex",
                        "roof_type": "flat",
                        "design_sources": {"roof_type": "AI_PLANNED"},
                    },
                },
                {
                    "component_type": "ROOF",
                    "slot_id": "b",
                    "relative_position": {"x": 20, "y": 6, "z": 0},
                    "dimensions": {"width": 12, "depth": 10, "height": 2},
                    "features": [],
                    "params": {"component_id": "roof_b", "host_id": "annex", "roof_type": "flat"},
                },
            ],
        }
        out = _apply_user_explicit_design_sources(copy.deepcopy(plan), "所有建筑都改成歇山顶。")
        main = next(c for c in out["components"] if c["params"]["component_id"] == "main_house")
        annex = next(c for c in out["components"] if c["params"]["component_id"] == "annex")
        roof_a = next(c for c in out["components"] if c["params"]["component_id"] == "roof_a")
        roof_b = next(c for c in out["components"] if c["params"]["component_id"] == "roof_b")
        self.assertEqual("xieshan", main["params"]["roof_type"])
        self.assertEqual("xieshan", annex["params"]["roof_type"])
        self.assertEqual("xieshan", roof_a["params"]["roof_type"])
        self.assertEqual("xieshan", roof_b["params"]["roof_type"])
        self.assertEqual("USER_EXPLICIT", out["design_sources"]["roof_type"])
        self.assertNotEqual("capability_gap", out.get("plan_status"))

    def test_double_eave_xieshan_maps_to_executable_shape_and_eave_params(self):
        from app.services.llm_plan_normalization import _apply_user_explicit_design_sources
        from app.services.generation_capability import apply_stage_b_capability_repair

        plan = {
            "mode": "build",
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "hall",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 20, "depth": 14, "height": 10},
                    "features": [],
                    "params": {"component_id": "hall", "roof_type": "gable"},
                },
                {
                    "component_type": "ROOF",
                    "slot_id": "hall",
                    "relative_position": {"x": 0, "y": 10, "z": 0},
                    "dimensions": {"width": 22, "depth": 16, "height": 5},
                    "features": [],
                    "params": {"component_id": "roof_hall", "host_id": "hall", "roof_type": "gable"},
                },
            ],
        }
        out = _apply_user_explicit_design_sources(copy.deepcopy(plan), "生成重檐歇山顶大殿")
        hall = next(c for c in out["components"] if c["params"]["component_id"] == "hall")
        roof = next(c for c in out["components"] if c["params"]["component_id"] == "roof_hall")
        self.assertEqual("xieshan", hall["params"]["roof_type"])
        self.assertTrue(hall["params"].get("double_eave"))
        self.assertEqual(2, hall["params"].get("eave_tiers"))
        self.assertEqual("xieshan", roof["params"]["roof_type"])
        self.assertTrue(roof["params"].get("double_eave"))

        composite = copy.deepcopy(plan)
        composite["components"][0]["params"]["roof_type"] = "double_eave_xieshan"
        composite["components"][1]["params"]["roof_type"] = "double_eave_xieshan"
        repaired, errors = apply_stage_b_capability_repair(composite, "生成重檐歇山顶大殿")
        self.assertFalse(any("unsupported roof_type" in e for e in errors))
        self.assertEqual("xieshan", repaired["components"][0]["params"]["roof_type"])
        self.assertTrue(repaired["components"][0]["params"].get("double_eave"))

    def test_local_roof_omit_stamps_mass_requirement_against_plan_required(self):
        from app.services.llm_plan_normalization import _apply_user_explicit_design_sources

        plan = {
            "mode": "build",
            "plan_meta": {"design_requirements": {"roof": "REQUIRED"}},
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "a",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 12, "depth": 10, "height": 8},
                    "features": [],
                    "params": {"component_id": "mass_a", "requirement_scope": "building_1", "roof_type": "gable"},
                },
                {
                    "component_type": "ROOF",
                    "slot_id": "a",
                    "relative_position": {"x": 0, "y": 8, "z": 0},
                    "dimensions": {"width": 14, "depth": 12, "height": 3},
                    "features": [],
                    "params": {"component_id": "roof_a", "host_id": "mass_a", "roof_type": "gable"},
                },
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "b",
                    "relative_position": {"x": 20, "y": 0, "z": 0},
                    "dimensions": {"width": 10, "depth": 8, "height": 6},
                    "features": [],
                    "params": {"component_id": "mass_b", "requirement_scope": "building_2", "roof_type": "flat"},
                },
            ],
        }
        out = _apply_user_explicit_design_sources(
            copy.deepcopy(plan), "第一栋不要屋顶；第二栋使用平屋顶。"
        )
        a = next(c for c in out["components"] if c["params"]["component_id"] == "mass_a")
        b = next(c for c in out["components"] if c["params"]["component_id"] == "mass_b")
        self.assertEqual("none", a["params"]["roof_type"])
        self.assertEqual("OMITTED", a["params"]["roof_requirement"])
        self.assertEqual("REQUIRED", out["plan_meta"]["design_requirements"]["roof"])
        self.assertEqual("flat", b["params"]["roof_type"])
        self.assertFalse(any(c.get("params", {}).get("component_id") == "roof_a" for c in out["components"]))

    def test_unknown_host_is_not_rewired_to_first_mass(self):
        from app.services.generation_capability import apply_stage_b_capability_repair

        plan = {
            "mode": "build",
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "a",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 12, "depth": 10, "height": 8},
                    "features": [],
                    "params": {"component_id": "main_house"},
                },
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "b",
                    "relative_position": {"x": 20, "y": 0, "z": 0},
                    "dimensions": {"width": 10, "depth": 8, "height": 6},
                    "features": [],
                    "params": {"component_id": "annex"},
                },
                {
                    "component_type": "ENTRANCE",
                    "slot_id": "b",
                    "relative_position": {"x": 24, "y": 0, "z": 7},
                    "dimensions": {"width": 2, "depth": 1, "height": 3},
                    "features": [],
                    "params": {"component_id": "door_annex", "host_id": "missing_annex_host"},
                },
                {
                    "component_type": "ROOF",
                    "slot_id": "b",
                    "relative_position": {"x": 20, "y": 6, "z": 0},
                    "dimensions": {"width": 12, "depth": 10, "height": 2},
                    "features": [],
                    "params": {"component_id": "roof_annex", "host_id": "ghost_wing", "roof_type": "flat"},
                },
            ],
        }
        repaired, errors = apply_stage_b_capability_repair(copy.deepcopy(plan), "两栋住宅")
        door = next(c for c in repaired["components"] if c["params"]["component_id"] == "door_annex")
        roof = next(c for c in repaired["components"] if c["params"]["component_id"] == "roof_annex")
        self.assertEqual("missing_annex_host", door["params"]["host_id"])
        self.assertEqual("ghost_wing", roof["params"]["host_id"])
        self.assertNotEqual("main_house", door["params"]["host_id"])
        self.assertTrue(any("host" in e.lower() for e in errors))

    def test_pavilion_xieshan_does_not_rewrite_adjacent_house(self):
        """Inferred roof scope must not upgrade to all_main_masses."""
        from app.services.building_contract import extract_requirements
        from app.services.llm_plan_normalization import _apply_user_explicit_design_sources

        text = "建一栋现代住宅，旁边有座歇山顶亭子"
        reqs = [r for r in extract_requirements(text) if r.get("property") == "roof_type"]
        self.assertTrue(reqs)
        self.assertTrue(all(r.get("scope") != "all_main_masses" for r in reqs))
        # Clause-local 亭子 may resolve as named_pavilion; must not escalate to whole compound.
        self.assertTrue(all(
            r.get("scope_source") in ("INFERRED", "NAMED") or r.get("status") == "unresolved"
            for r in reqs
        ))

        plan = {
            "mode": "build",
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "a",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 14, "depth": 12, "height": 8},
                    "features": [],
                    "params": {
                        "component_id": "modern_house",
                        "roof_type": "flat",
                        "design_sources": {"roof_type": "AI_PLANNED"},
                    },
                },
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "b",
                    "relative_position": {"x": 22, "y": 0, "z": 0},
                    "dimensions": {"width": 6, "depth": 6, "height": 4},
                    "features": ["pavilion"],
                    "params": {
                        "component_id": "garden_pavilion",
                        "roof_type": "xieshan",
                        "design_sources": {"roof_type": "AI_PLANNED"},
                    },
                },
            ],
        }
        out = _apply_user_explicit_design_sources(copy.deepcopy(plan), text)
        house = next(c for c in out["components"] if c["params"]["component_id"] == "modern_house")
        pavilion = next(c for c in out["components"] if c["params"]["component_id"] == "garden_pavilion")
        self.assertEqual("flat", house["params"]["roof_type"])
        self.assertEqual("xieshan", pavilion["params"]["roof_type"])
        self.assertNotEqual("USER_EXPLICIT", (out.get("design_sources") or {}).get("roof_type"))

    def test_main_hall_xieshan_and_annex_flat_do_not_cross_cover(self):
        from app.services.llm_plan_normalization import _apply_user_explicit_design_sources

        text = "主殿采用歇山顶，配殿采用平顶"
        plan = {
            "mode": "build",
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "a",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 18, "depth": 14, "height": 10},
                    "features": [],
                    "params": {
                        "component_id": "main_hall",
                        "roof_type": "xieshan",
                        "design_sources": {"roof_type": "AI_PLANNED"},
                    },
                },
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "b",
                    "relative_position": {"x": 24, "y": 0, "z": 0},
                    "dimensions": {"width": 10, "depth": 8, "height": 6},
                    "features": [],
                    "params": {
                        "component_id": "annex_hall",
                        "roof_type": "flat",
                        "design_sources": {"roof_type": "AI_PLANNED"},
                    },
                },
            ],
        }
        out = _apply_user_explicit_design_sources(copy.deepcopy(plan), text)
        main = next(c for c in out["components"] if c["params"]["component_id"] == "main_hall")
        annex = next(c for c in out["components"] if c["params"]["component_id"] == "annex_hall")
        self.assertEqual("xieshan", main["params"]["roof_type"])
        self.assertEqual("flat", annex["params"]["roof_type"])
        self.assertNotEqual("USER_EXPLICIT", (out.get("design_sources") or {}).get("roof_type"))

    def test_main_hall_xieshan_and_flat_pavilion_do_not_cross_bind(self):
        from app.services.llm_plan_normalization import _apply_user_explicit_design_sources

        def run(text: str):
            plan = {
                "mode": "build",
                "components": [
                    {
                        "component_type": "MASS_MAIN",
                        "slot_id": "hall",
                        "relative_position": {"x": 0, "y": 0, "z": 0},
                        "dimensions": {"width": 18, "depth": 14, "height": 10},
                        "features": [],
                        "params": {
                            "component_id": "main_hall",
                            "roof_type": "gable",
                            "design_sources": {"roof_type": "AI_PLANNED"},
                        },
                    },
                    {
                        "component_type": "MASS_MAIN",
                        "slot_id": "ting",
                        "relative_position": {"x": 24, "y": 0, "z": 0},
                        "dimensions": {"width": 8, "depth": 8, "height": 5},
                        "features": [],
                        "params": {
                            "component_id": "garden_pavilion",
                            "roof_type": "flat",
                            "design_sources": {"roof_type": "AI_PLANNED"},
                        },
                    },
                ],
            }
            return _apply_user_explicit_design_sources(copy.deepcopy(plan), text)

        for text in (
            "建一座主殿，主殿采用歇山顶，旁边有一座平顶小亭",
            "旁边有一座平顶小亭，建一座主殿，主殿采用歇山顶",
        ):
            with self.subTest(text=text):
                out = run(text)
                hall = next(c for c in out["components"] if c["params"]["component_id"] == "main_hall")
                pavilion = next(
                    c for c in out["components"] if c["params"]["component_id"] == "garden_pavilion"
                )
                self.assertEqual("xieshan", hall["params"]["roof_type"], text)
                self.assertEqual("flat", pavilion["params"]["roof_type"], text)
                self.assertEqual("USER_EXPLICIT", hall["params"]["design_sources"]["roof_type"])
                self.assertEqual("USER_EXPLICIT", pavilion["params"]["design_sources"]["roof_type"])

    def test_infer_unique_named_roof_host_ignores_other_clause_cues(self):
        from app.services.llm_plan_normalization import _infer_unique_named_roof_host

        masses = [
            {
                "component_type": "MASS_MAIN",
                "params": {"component_id": "main_hall"},
            },
            {
                "component_type": "MASS_MAIN",
                "params": {"component_id": "garden_pavilion"},
            },
        ]
        req = {
            "value": "xieshan",
            "source_text": ["歇山顶"],
            "scope": "unresolved",
            "scope_source": "INFERRED",
        }
        # Whole prompt mentions 小亭, but the 歇山 clause subject is 主殿 — must not bind pavilion.
        text = "建一座主殿，主殿采用歇山顶，旁边有一座平顶小亭"
        hits = _infer_unique_named_roof_host(masses, req, user_text=text)
        self.assertEqual(1, len(hits))
        self.assertEqual("main_hall", hits[0]["params"]["component_id"])

    def test_duplicate_xieshan_declarations_keep_both_hosts(self):
        from app.services.building_contract import extract_requirements
        from app.services.llm_plan_normalization import _apply_user_explicit_design_sources

        text = "主殿采用歇山顶，旁边的亭子也采用歇山顶。"
        roofs = [r for r in extract_requirements(text) if r.get("property") == "roof_type" and r.get("value") == "xieshan"]
        self.assertGreaterEqual(len(roofs), 2, roofs)
        scopes = {r.get("scope") for r in roofs}
        self.assertIn("named_main_hall", scopes)
        self.assertIn("named_pavilion", scopes)

        plan = {
            "mode": "build",
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "hall",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 14, "depth": 12, "height": 8},
                    "features": [],
                    "params": {
                        "component_id": "main_hall",
                        "roof_type": "xieshan",
                        "design_sources": {"roof_type": "AI_PLANNED"},
                    },
                },
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "ting",
                    "relative_position": {"x": 24, "y": 0, "z": 0},
                    "dimensions": {"width": 8, "depth": 8, "height": 5},
                    "features": [],
                    "params": {
                        "component_id": "garden_pavilion",
                        "roof_type": "gable",
                        "design_sources": {"roof_type": "AI_PLANNED"},
                    },
                },
            ],
        }
        out = _apply_user_explicit_design_sources(copy.deepcopy(plan), text)
        hall = next(c for c in out["components"] if c["params"]["component_id"] == "main_hall")
        pavilion = next(c for c in out["components"] if c["params"]["component_id"] == "garden_pavilion")
        self.assertEqual("xieshan", hall["params"]["roof_type"])
        self.assertEqual("xieshan", pavilion["params"]["roof_type"])
        self.assertEqual("USER_EXPLICIT", pavilion["params"]["design_sources"]["roof_type"])

    def test_joint_multi_subject_xieshan_binds_both_or_gaps(self):
        from app.services.building_contract import extract_requirements
        from app.services.llm_plan_normalization import _apply_user_explicit_design_sources, _targets_for_req

        text = "主殿和亭子都采用歇山顶。"
        roofs = [r for r in extract_requirements(text) if r.get("property") == "roof_type"]
        self.assertTrue(any(r.get("scope") == "multi_named" for r in roofs), roofs)
        multi = next(r for r in roofs if r.get("scope") == "multi_named")
        self.assertEqual("EXPLICIT_MULTI", multi.get("scope_source"))
        self.assertIn("main_hall", multi.get("named_keys") or [])
        self.assertIn("pavilion", multi.get("named_keys") or [])

        masses = [
            {"component_type": "MASS_MAIN", "params": {"component_id": "main_hall", "roof_type": "gable"}},
            {"component_type": "MASS_MAIN", "params": {"component_id": "garden_pavilion", "roof_type": "flat"}},
        ]
        hits = _targets_for_req(masses, multi)
        self.assertEqual({"main_hall", "garden_pavilion"}, {m["params"]["component_id"] for m in hits})

        plan = {
            "mode": "build",
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "hall",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 14, "depth": 12, "height": 8},
                    "features": [],
                    "params": {
                        "component_id": "main_hall",
                        "roof_type": "gable",
                        "design_sources": {"roof_type": "AI_PLANNED"},
                    },
                },
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "ting",
                    "relative_position": {"x": 24, "y": 0, "z": 0},
                    "dimensions": {"width": 8, "depth": 8, "height": 5},
                    "features": [],
                    "params": {
                        "component_id": "garden_pavilion",
                        "roof_type": "flat",
                        "design_sources": {"roof_type": "AI_PLANNED"},
                    },
                },
            ],
        }
        out = _apply_user_explicit_design_sources(copy.deepcopy(plan), text)
        hall = next(c for c in out["components"] if c["params"]["component_id"] == "main_hall")
        pavilion = next(c for c in out["components"] if c["params"]["component_id"] == "garden_pavilion")
        self.assertEqual("xieshan", hall["params"]["roof_type"])
        self.assertEqual("xieshan", pavilion["params"]["roof_type"])

    def test_joint_multi_named_missing_pavilion_is_not_partial_success(self):
        from app.services.building_contract import extract_requirements, apply_building_contract
        from app.services.llm_plan_normalization import _apply_user_explicit_design_sources, _targets_for_req

        text = "主殿和亭子都采用歇山顶。"
        multi = next(
            r for r in extract_requirements(text)
            if r.get("property") == "roof_type" and r.get("scope") == "multi_named"
        )
        masses = [
            {"component_type": "MASS_MAIN", "params": {"component_id": "main_hall", "roof_type": "gable"}},
        ]
        self.assertEqual([], _targets_for_req(masses, multi))

        plan = {
            "mode": "build",
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "hall",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 14, "depth": 12, "height": 8},
                    "features": [],
                    "params": {
                        "component_id": "main_hall",
                        "roof_type": "gable",
                        "design_sources": {"roof_type": "AI_PLANNED"},
                    },
                },
            ],
        }
        out = _apply_user_explicit_design_sources(copy.deepcopy(plan), text)
        hall = out["components"][0]
        self.assertEqual("gable", hall["params"]["roof_type"], "partial multi_named must not stamp hall alone")
        self.assertEqual("AI_PLANNED", hall["params"]["design_sources"]["roof_type"])
        diags = (out.get("plan_meta") or {}).get("scope_diagnostics") or []
        self.assertTrue(any(d.get("code") == "E_ROOF_HOST_UNRESOLVED" for d in diags), diags)

        contracted = apply_building_contract(copy.deepcopy(plan), text, finalize=True)
        multi_req = next(
            r for r in (contracted.get("proportion_hints") or {})
            .get("building_contract", {})
            .get("requirements", [])
            if r.get("scope") == "multi_named"
        )
        self.assertIn(multi_req.get("status"), ("unresolved_binding", "unverified"))
        self.assertTrue(
            any(
                d.get("code") == "E_REQUIREMENT_SCOPE" and d.get("scope") == "multi_named"
                for d in (contracted.get("proportion_hints") or {})
                .get("building_contract", {})
                .get("diagnostics", [])
            )
        )

    def test_ordinal_omit_match_span_uses_full_text_coords(self):
        from app.services.building_contract import extract_requirements

        text = "第一栋不要屋顶；第二栋不要屋顶。"
        omits = [
            r for r in extract_requirements(text)
            if r.get("property") == "roof_type" and r.get("value") == "none"
        ]
        self.assertGreaterEqual(len(omits), 2, omits)
        spans = []
        for req in omits:
            span = req.get("match_span")
            self.assertIsInstance(span, list)
            self.assertEqual(2, len(span))
            start, end = int(span[0]), int(span[1])
            self.assertGreaterEqual(start, 0)
            self.assertLessEqual(end, len(text))
            self.assertEqual(text[start:end], (req.get("source_text") or [""])[0])
            spans.append((start, end))
        self.assertEqual(len(spans), len(set(spans)), "duplicate omit spans must stay distinct")

    def test_newline_and_cjk_space_preserve_original_match_span(self):
        from app.services.building_contract import extract_requirements

        text = "主殿不要屋顶\n亭子 也不要屋顶"
        omits = [
            r for r in extract_requirements(text)
            if r.get("property") == "roof_type" and r.get("value") == "none"
        ]
        self.assertGreaterEqual(len(omits), 2, omits)
        for req in omits:
            start, end = req["match_span"]
            self.assertEqual(text[start:end], (req.get("source_text") or [""])[0])
        scopes = {str(r.get("scope") or "") for r in omits}
        self.assertTrue(any("main_hall" in s or "hall" in s for s in scopes), scopes)
        self.assertTrue(any("pavilion" in s for s in scopes), scopes)

    def test_newline_joint_quantifier_does_not_merge_across_lines(self):
        from app.services.building_contract import extract_requirements

        text = "主殿不要屋顶\n亭子都不要屋顶"
        omits = [
            r for r in extract_requirements(text)
            if r.get("property") == "roof_type" and r.get("value") == "none"
        ]
        self.assertGreaterEqual(len(omits), 2, omits)
        scopes = [str(r.get("scope") or "") for r in omits]
        self.assertNotIn("multi_named", scopes, scopes)
        self.assertTrue(any("main_hall" in s or "hall" in s for s in scopes), scopes)
        self.assertTrue(any("pavilion" in s for s in scopes), scopes)

    def test_plate_secondary_excluded_from_design_constraint_hosts(self):
        from app.services.building_contract import is_design_constraint_host
        from app.services.llm_plan_normalization import _is_design_constraint_host

        house = {
            "component_type": "MASS_MAIN",
            "params": {"component_id": "house_main"},
        }
        plate = {
            "component_type": "MASS_SECONDARY",
            "params": {"component_id": "deck_plate", "extrude_mode": "plate"},
        }
        attached = {
            "component_type": "MASS_SECONDARY",
            "params": {"component_id": "wing_mass", "host_id": "house_main"},
        }
        bare_structure = {
            "component_type": "STRUCTURE",
            "params": {"component_id": "porch_column"},
            "features": [],
        }
        typology_structure = {
            "component_type": "STRUCTURE",
            "params": {"component_id": "pagoda"},
            "features": ["typology:dense_eaves_pagoda"],
        }
        self.assertTrue(_is_design_constraint_host(house))
        self.assertTrue(is_design_constraint_host(house))
        self.assertFalse(_is_design_constraint_host(plate))
        self.assertFalse(_is_design_constraint_host(attached))
        self.assertFalse(_is_design_constraint_host(bare_structure))
        self.assertTrue(_is_design_constraint_host(typology_structure))

    def test_building_contract_all_masses_includes_tower(self):
        from app.services.building_contract import apply_building_contract, design_constraint_hosts

        text = "建造一座主殿和一座塔楼，所有建筑都不要屋顶。"
        plan = {
            "mode": "build",
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "hall",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 14, "depth": 12, "height": 8},
                    "features": [],
                    "params": {
                        "component_id": "main_hall",
                        "roof_type": "xieshan",
                        "design_sources": {"roof_type": "AI_PLANNED"},
                    },
                },
                {
                    "component_type": "TOWER",
                    "slot_id": "tower",
                    "relative_position": {"x": 24, "y": 0, "z": 0},
                    "dimensions": {"width": 6, "depth": 6, "height": 16},
                    "features": [],
                    "params": {
                        "component_id": "bell_tower",
                        "roof_type": "hip",
                        "design_sources": {"roof_type": "AI_PLANNED"},
                    },
                },
            ],
        }
        hosts = {c["params"]["component_id"] for c in design_constraint_hosts(plan["components"])}
        self.assertEqual({"main_hall", "bell_tower"}, hosts)
        out = apply_building_contract(plan, text)
        contract = (out.get("proportion_hints") or {}).get("building_contract") or {}
        omits = [
            r for r in (contract.get("requirements") or [])
            if r.get("property") == "roof_type" and r.get("value") == "none"
        ]
        self.assertTrue(omits, omits)
        targets = set(omits[0].get("target_components") or [])
        self.assertEqual({"main_hall", "bell_tower"}, targets, omits[0])

    def test_standalone_tower_omit_is_not_ignored_by_contract(self):
        from app.services.building_contract import apply_building_contract

        text = "塔楼不要屋顶。"
        plan = {
            "mode": "build",
            "components": [
                {
                    "component_type": "TOWER",
                    "slot_id": "tower",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 6, "depth": 6, "height": 16},
                    "features": [],
                    "params": {
                        "component_id": "bell_tower",
                        "roof_type": "hip",
                        "design_sources": {"roof_type": "AI_PLANNED"},
                    },
                },
            ],
        }
        out = apply_building_contract(plan, text)
        contract = (out.get("proportion_hints") or {}).get("building_contract") or {}
        omits = [
            r for r in (contract.get("requirements") or [])
            if r.get("property") == "roof_type" and r.get("value") == "none"
        ]
        self.assertTrue(omits, omits)
        self.assertIn("bell_tower", set(omits[0].get("target_components") or []), omits[0])

    def test_typology_structure_omit_writes_params(self):
        from app.services.llm_plan_normalization import _apply_user_explicit_design_sources

        text = "不要屋顶"
        plan = {
            "mode": "build",
            "components": [
                {
                    "component_type": "STRUCTURE",
                    "slot_id": "pagoda",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 10, "depth": 10, "height": 18},
                    "features": ["typology:dense_eaves_pagoda"],
                    "params": {
                        "component_id": "pagoda_shell",
                        "roof_type": "xieshan",
                        "design_sources": {"roof_type": "AI_PLANNED"},
                    },
                },
            ],
        }
        out = _apply_user_explicit_design_sources(plan, text)
        params = out["components"][0]["params"]
        self.assertEqual("none", params.get("roof_type"))
        sources = params.get("design_sources") or {}
        self.assertEqual("USER_EXPLICIT", str(sources.get("roof_type") or "").upper())

    def test_hall_plus_typology_structure_all_mass_targets_agree(self):
        from app.services.building_contract import apply_building_contract, design_constraint_hosts

        text = "所有建筑都不要屋顶。"
        plan = {
            "mode": "build",
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "hall",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 14, "depth": 12, "height": 8},
                    "features": [],
                    "params": {"component_id": "main_hall", "roof_type": "xieshan"},
                },
                {
                    "component_type": "STRUCTURE",
                    "slot_id": "pagoda",
                    "relative_position": {"x": 30, "y": 0, "z": 0},
                    "dimensions": {"width": 10, "depth": 10, "height": 18},
                    "features": ["typology:dense_eaves_pagoda"],
                    "params": {"component_id": "pagoda_shell", "roof_type": "xieshan"},
                },
            ],
        }
        host_ids = {c["params"]["component_id"] for c in design_constraint_hosts(plan["components"])}
        self.assertEqual({"main_hall", "pagoda_shell"}, host_ids)
        out = apply_building_contract(plan, text)
        contract = (out.get("proportion_hints") or {}).get("building_contract") or {}
        omits = [
            r for r in (contract.get("requirements") or [])
            if r.get("property") == "roof_type" and r.get("value") == "none"
        ]
        self.assertTrue(omits, omits)
        self.assertEqual(host_ids, set(omits[0].get("target_components") or []), omits[0])

    def test_workshop_boiler_multi_named_omit_extracted(self):
        from app.services.building_contract import extract_requirements

        text = "主车间和锅炉房都不要屋顶。"
        omits = [
            r for r in extract_requirements(text)
            if r.get("property") == "roof_type" and r.get("value") == "none"
        ]
        self.assertTrue(omits, omits)
        self.assertEqual("multi_named", omits[0].get("scope"))
        keys = set(omits[0].get("named_keys") or [])
        self.assertIn("workshop", keys)
        self.assertIn("boiler", keys)

    def test_all_buildings_omit_roof_is_explicit_quantifier(self):
        from app.services.building_contract import extract_requirements

        text = "建造两栋建筑，所有建筑都不要屋顶。"
        omits = [
            r for r in extract_requirements(text)
            if r.get("property") == "roof_type" and r.get("value") == "none"
        ]
        self.assertTrue(omits, omits)
        self.assertEqual("all_main_masses", omits[0].get("scope"))
        self.assertEqual("EXPLICIT_QUANTIFIER", omits[0].get("scope_source"))

    def test_dual_roof_omit_declarations_bind_independently(self):
        from app.services.building_contract import extract_requirements, apply_building_contract
        from app.services.llm_plan_normalization import _apply_user_explicit_design_sources

        text = "主殿不要屋顶，旁边的亭子也不要屋顶。"
        omits = [
            r for r in extract_requirements(text)
            if r.get("property") == "roof_type" and r.get("value") == "none"
        ]
        self.assertGreaterEqual(len(omits), 2, omits)
        scopes = {str(r.get("scope") or "") for r in omits}
        self.assertTrue(any("hall" in s or "main" in s for s in scopes), scopes)
        self.assertTrue(any("pavilion" in s or "ting" in s for s in scopes), scopes)

        plan = {
            "mode": "build",
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "hall",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 14, "depth": 12, "height": 8},
                    "features": [],
                    "params": {
                        "component_id": "main_hall",
                        "roof_type": "xieshan",
                        "design_sources": {"roof_type": "AI_PLANNED"},
                    },
                },
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "ting",
                    "relative_position": {"x": 24, "y": 0, "z": 0},
                    "dimensions": {"width": 8, "depth": 8, "height": 5},
                    "features": [],
                    "params": {
                        "component_id": "garden_pavilion",
                        "roof_type": "flat",
                        "design_sources": {"roof_type": "AI_PLANNED"},
                    },
                },
            ],
        }
        out = _apply_user_explicit_design_sources(copy.deepcopy(plan), text)
        hall = next(c for c in out["components"] if c["params"]["component_id"] == "main_hall")
        pavilion = next(c for c in out["components"] if c["params"]["component_id"] == "garden_pavilion")
        self.assertEqual("none", hall["params"]["roof_type"])
        self.assertEqual("none", pavilion["params"]["roof_type"])
        contracted = apply_building_contract(copy.deepcopy(plan), text, finalize=True)
        roof_omits = [
            r for r in (contracted.get("proportion_hints") or {})
            .get("building_contract", {})
            .get("requirements", [])
            if r.get("property") == "roof_type" and r.get("value") == "none"
        ]
        target_sets = [set(r.get("target_components") or []) for r in roof_omits]
        self.assertTrue(any(t == {"main_hall"} for t in target_sets), target_sets)
        self.assertTrue(any(t == {"garden_pavilion"} for t in target_sets), target_sets)

    def test_double_negation_window_omit_not_extracted(self):
        from app.services.building_contract import extract_requirements

        text = "建一座不是无窗的建筑，要有很多窗户"
        omits = [
            r for r in extract_requirements(text)
            if r.get("property") == "window_style" and r.get("value") == "none"
        ]
        self.assertEqual([], omits)

    def test_pavilion_beside_hall_roof_omit_binds_pavilion(self):
        from app.services.building_contract import extract_requirements
        from app.services.llm_plan_normalization import _apply_user_explicit_design_sources

        text = "主殿旁的亭子不要屋顶。"
        roofs = [r for r in extract_requirements(text) if r.get("property") == "roof_type" and r.get("value") == "none"]
        self.assertEqual(1, len(roofs), roofs)
        self.assertEqual("named_pavilion", roofs[0].get("scope"))

        plan = {
            "mode": "build",
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "hall",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 14, "depth": 12, "height": 8},
                    "features": [],
                    "params": {
                        "component_id": "main_hall",
                        "roof_type": "xieshan",
                        "design_sources": {"roof_type": "AI_PLANNED"},
                    },
                },
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "ting",
                    "relative_position": {"x": 24, "y": 0, "z": 0},
                    "dimensions": {"width": 8, "depth": 8, "height": 5},
                    "features": [],
                    "params": {
                        "component_id": "garden_pavilion",
                        "roof_type": "flat",
                        "design_sources": {"roof_type": "AI_PLANNED"},
                    },
                },
            ],
        }
        out = _apply_user_explicit_design_sources(copy.deepcopy(plan), text)
        hall = next(c for c in out["components"] if c["params"]["component_id"] == "main_hall")
        pavilion = next(c for c in out["components"] if c["params"]["component_id"] == "garden_pavilion")
        self.assertEqual("xieshan", hall["params"]["roof_type"])
        self.assertEqual("none", pavilion["params"]["roof_type"])
        self.assertEqual("USER_EXPLICIT", pavilion["params"]["design_sources"]["roof_type"])

    def test_unknown_japanese_garden_roof_not_replaced_with_xieshan(self):
        from app.services.generation_capability import apply_stage_b_capability_repair

        plan = {
            "mode": "build",
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "a",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 10, "depth": 10, "height": 5},
                    "features": [],
                    "params": {"component_id": "teahouse", "roof_type": "origami_wave_roof"},
                },
            ],
        }
        repaired, errors = apply_stage_b_capability_repair(
            copy.deepcopy(plan), "生成一座日式庭院茶室，采用原创波浪屋顶"
        )
        teahouse = next(c for c in repaired["components"] if c["params"]["component_id"] == "teahouse")
        self.assertEqual("origami_wave_roof", teahouse["params"]["roof_type"])
        self.assertTrue(any("unsupported roof_type" in e for e in errors))
        repairs = (repaired.get("plan_meta") or {}).get("capability_repairs") or []
        self.assertFalse(any("locale fallback" in str(r) for r in repairs))
        self.assertFalse(any("→xieshan" in str(r) for r in repairs))

    def test_unbound_roof_blocks_global_user_explicit_lock(self):
        from app.services.llm_plan_normalization import _apply_user_explicit_design_sources

        plan = {
            "mode": "build",
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "a",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 12, "depth": 10, "height": 8},
                    "features": [],
                    "params": {
                        "component_id": "main_house",
                        "roof_type": "gable",
                        "design_sources": {"roof_type": "AI_PLANNED"},
                    },
                },
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "b",
                    "relative_position": {"x": 20, "y": 0, "z": 0},
                    "dimensions": {"width": 10, "depth": 8, "height": 6},
                    "features": [],
                    "params": {
                        "component_id": "annex",
                        "roof_type": "flat",
                        "design_sources": {"roof_type": "AI_PLANNED"},
                    },
                },
                {
                    "component_type": "ROOF",
                    "slot_id": "orphan",
                    "relative_position": {"x": 0, "y": 8, "z": 0},
                    "dimensions": {"width": 14, "depth": 12, "height": 3},
                    "features": [],
                    "params": {"component_id": "roof_orphan", "roof_type": "gable"},
                },
            ],
        }
        out = _apply_user_explicit_design_sources(copy.deepcopy(plan), "所有建筑都改成歇山顶。")
        main = next(c for c in out["components"] if c["params"]["component_id"] == "main_house")
        annex = next(c for c in out["components"] if c["params"]["component_id"] == "annex")
        orphan = next(c for c in out["components"] if c["params"]["component_id"] == "roof_orphan")
        self.assertEqual("xieshan", main["params"]["roof_type"])
        self.assertEqual("xieshan", annex["params"]["roof_type"])
        self.assertEqual("gable", orphan["params"]["roof_type"])
        self.assertNotEqual("USER_EXPLICIT", (out.get("design_sources") or {}).get("roof_type"))
        diags = (out.get("plan_meta") or {}).get("scope_diagnostics") or []
        self.assertTrue(any(d.get("code") == "E_ROOF_COMPONENT_INCONSISTENT" for d in diags))

    def test_ambiguous_host_substring_is_not_auto_bound(self):
        from app.services.generation_capability import apply_stage_b_capability_repair

        plan = {
            "mode": "build",
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "a",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 12, "depth": 10, "height": 8},
                    "features": [],
                    "params": {"component_id": "court_north"},
                },
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "b",
                    "relative_position": {"x": 20, "y": 0, "z": 0},
                    "dimensions": {"width": 10, "depth": 8, "height": 6},
                    "features": [],
                    "params": {"component_id": "court_south"},
                },
                {
                    "component_type": "COURTYARD",
                    "slot_id": "c",
                    "relative_position": {"x": 8, "y": 0, "z": 8},
                    "dimensions": {"width": 8, "depth": 8, "height": 1},
                    "features": [],
                    "params": {"component_id": "yard_plaza"},
                },
                {
                    "component_type": "ROOF",
                    "slot_id": "r",
                    "relative_position": {"x": 0, "y": 8, "z": 0},
                    "dimensions": {"width": 12, "depth": 10, "height": 2},
                    "features": [],
                    "params": {"component_id": "roof_x", "host_id": "court", "roof_type": "gable"},
                },
            ],
        }
        repaired, errors = apply_stage_b_capability_repair(copy.deepcopy(plan), "庭院建筑")
        roof = next(c for c in repaired["components"] if c["params"]["component_id"] == "roof_x")
        self.assertEqual("court", roof["params"]["host_id"])
        self.assertTrue(any("host" in e.lower() for e in errors))

    def test_strict_mode_handoff_fields_for_house_and_pavilion(self):
        """Python→Java handoff: pavilion xieshan must not rewrite house; AI none stays AI_PLANNED."""
        from app.services.llm_plan_normalization import _apply_user_explicit_design_sources

        plan = {
            "mode": "build",
            "plan_meta": {
                "allow_compiler_satellites": False,
                "design_requirements": {"roof": "REQUIRED", "facade": "OMITTED", "entrance": "OMITTED"},
            },
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "a",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 14, "depth": 12, "height": 8},
                    "features": [],
                    "params": {
                        "component_id": "modern_house",
                        "roof_type": "flat",
                        "design_sources": {"roof_type": "AI_PLANNED"},
                    },
                },
                {
                    "component_type": "ROOF",
                    "slot_id": "a",
                    "relative_position": {"x": 0, "y": 8, "z": 0},
                    "dimensions": {"width": 16, "depth": 14, "height": 2},
                    "features": [],
                    "params": {
                        "component_id": "roof_house",
                        "host_id": "modern_house",
                        "roof_type": "flat",
                        "design_sources": {"roof_type": "AI_PLANNED"},
                    },
                },
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "b",
                    "relative_position": {"x": 22, "y": 0, "z": 0},
                    "dimensions": {"width": 6, "depth": 6, "height": 4},
                    "features": ["pavilion"],
                    "params": {
                        "component_id": "garden_pavilion",
                        "roof_type": "xieshan",
                        "design_sources": {"roof_type": "AI_PLANNED"},
                    },
                },
                {
                    "component_type": "ROOF",
                    "slot_id": "b",
                    "relative_position": {"x": 22, "y": 4, "z": 0},
                    "dimensions": {"width": 8, "depth": 8, "height": 3},
                    "features": [],
                    "params": {
                        "component_id": "roof_pavilion",
                        "host_id": "garden_pavilion",
                        "roof_type": "xieshan",
                        "design_sources": {"roof_type": "AI_PLANNED"},
                    },
                },
            ],
        }
        out = _apply_user_explicit_design_sources(
            copy.deepcopy(plan), "建一栋现代住宅，旁边有座歇山顶亭子"
        )
        house = next(c for c in out["components"] if c["params"]["component_id"] == "modern_house")
        pavilion = next(c for c in out["components"] if c["params"]["component_id"] == "garden_pavilion")
        roof_h = next(c for c in out["components"] if c["params"]["component_id"] == "roof_house")
        roof_p = next(c for c in out["components"] if c["params"]["component_id"] == "roof_pavilion")
        self.assertEqual("flat", house["params"]["roof_type"])
        self.assertEqual("xieshan", pavilion["params"]["roof_type"])
        self.assertEqual("flat", roof_h["params"]["roof_type"])
        self.assertEqual("xieshan", roof_p["params"]["roof_type"])
        self.assertEqual("AI_PLANNED", house["params"]["design_sources"]["roof_type"])
        # Player「歇山顶亭子」binds uniquely to the pavilion host.
        self.assertEqual("USER_EXPLICIT", pavilion["params"]["design_sources"]["roof_type"])
        self.assertFalse(out["plan_meta"].get("allow_compiler_satellites"))
        self.assertEqual("REQUIRED", out["plan_meta"]["design_requirements"]["roof"])
        self.assertNotEqual("USER_EXPLICIT", (out.get("design_sources") or {}).get("roof_type"))

    def test_local_pavilion_no_windows_does_not_strip_house_windows(self):
        """Named pavilion window omit binds locally — must not escalate to every MASS."""
        from app.services.llm_plan_normalization import _apply_user_explicit_design_sources

        plan = {
            "mode": "build",
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "a",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 12, "depth": 10, "height": 6},
                    "features": [],
                    "params": {
                        "component_id": "modern_house",
                        "window_style": "regular",
                        "window_ratio": 0.2,
                        "design_sources": {"window_style": "AI_PLANNED"},
                    },
                },
                {
                    "component_type": "FACADE_WINDOWS",
                    "slot_id": "a",
                    "relative_position": {"x": 0, "y": 1, "z": 0},
                    "dimensions": {"width": 10, "depth": 1, "height": 3},
                    "features": [],
                    "params": {"component_id": "facade_house", "host_id": "modern_house"},
                },
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "b",
                    "relative_position": {"x": 20, "y": 0, "z": 0},
                    "dimensions": {"width": 8, "depth": 8, "height": 5},
                    "features": ["pavilion"],
                    "params": {
                        "component_id": "garden_pavilion",
                        "window_style": "regular",
                        "design_sources": {"window_style": "AI_PLANNED"},
                    },
                },
                {
                    "component_type": "FACADE_WINDOWS",
                    "slot_id": "b",
                    "relative_position": {"x": 20, "y": 1, "z": 0},
                    "dimensions": {"width": 6, "depth": 1, "height": 3},
                    "features": [],
                    "params": {"component_id": "facade_pavilion", "host_id": "garden_pavilion"},
                },
            ],
        }
        out = _apply_user_explicit_design_sources(
            copy.deepcopy(plan), "住宅旁边的亭子不开窗"
        )
        house = next(c for c in out["components"] if c["params"]["component_id"] == "modern_house")
        pavilion = next(c for c in out["components"] if c["params"]["component_id"] == "garden_pavilion")
        self.assertEqual("regular", house["params"]["window_style"])
        self.assertTrue(any(
            c.get("params", {}).get("component_id") == "facade_house"
            for c in out["components"]
        ))
        self.assertEqual("none", pavilion["params"].get("window_style"))
        self.assertNotEqual("capability_gap", out.get("plan_status"))

    def test_local_pavilion_no_roof_does_not_strip_house_roof(self):
        from app.services.llm_plan_normalization import _apply_user_explicit_design_sources

        plan = {
            "mode": "build",
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "a",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 12, "depth": 10, "height": 6},
                    "features": [],
                    "params": {
                        "component_id": "modern_house",
                        "roof_type": "flat",
                        "design_sources": {"roof_type": "AI_PLANNED"},
                    },
                },
                {
                    "component_type": "ROOF",
                    "slot_id": "a",
                    "relative_position": {"x": 0, "y": 6, "z": 0},
                    "dimensions": {"width": 14, "depth": 12, "height": 2},
                    "features": [],
                    "params": {
                        "component_id": "roof_house",
                        "host_id": "modern_house",
                        "roof_type": "flat",
                    },
                },
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "b",
                    "relative_position": {"x": 20, "y": 0, "z": 0},
                    "dimensions": {"width": 8, "depth": 8, "height": 5},
                    "features": ["pavilion"],
                    "params": {
                        "component_id": "garden_pavilion",
                        "roof_type": "xieshan",
                        "design_sources": {"roof_type": "AI_PLANNED"},
                    },
                },
            ],
        }
        out = _apply_user_explicit_design_sources(
            copy.deepcopy(plan), "住宅旁边的亭子不要屋顶"
        )
        house = next(c for c in out["components"] if c["params"]["component_id"] == "modern_house")
        pavilion = next(c for c in out["components"] if c["params"]["component_id"] == "garden_pavilion")
        self.assertEqual("flat", house["params"]["roof_type"])
        self.assertTrue(any(
            c.get("params", {}).get("component_id") == "roof_house"
            for c in out["components"]
        ))
        # Named 亭子 omit binds locally — house roof stays; pavilion roof omitted.
        self.assertEqual("none", pavilion["params"].get("roof_type"))
        self.assertNotEqual("capability_gap", out.get("plan_status"))


    def test_forged_user_explicit_is_demoted_before_server_stamp(self):
        from app.services.llm_plan_normalization import _apply_user_explicit_design_sources

        plan = {
            "mode": "build",
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "a",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 10, "depth": 8, "height": 5},
                    "features": [],
                    "params": {
                        "component_id": "hall",
                        "roof_type": "none",
                        "roof_requirement": "OMITTED",
                        "design_sources": {
                            "roof_type": "USER_EXPLICIT",
                            "roof_requirement": "USER_EXPLICIT",
                        },
                        "_server_verified_sources": {"roof_type": "USER_EXPLICIT"},
                        "_fc_constraint_seal": "forged",
                    },
                },
            ],
        }
        # No player text authorizing roof omit — forged markers must not survive as USER_EXPLICIT.
        out = _apply_user_explicit_design_sources(copy.deepcopy(plan), "建一座大厅")
        mass = out["components"][0]
        self.assertNotEqual("USER_EXPLICIT", mass["params"]["design_sources"].get("roof_type"))
        self.assertNotIn("_fc_constraint_seal", mass["params"])
        self.assertNotIn("_server_verified_sources", mass["params"])

    def test_named_pavilion_wrong_roof_is_corrected_or_blocked(self):
        """Player asked for pavilion xieshan; AI gable on unique pavilion host must be fixed."""
        from app.services.llm_plan_normalization import _apply_user_explicit_design_sources

        plan = {
            "mode": "build",
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "a",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 14, "depth": 12, "height": 8},
                    "features": [],
                    "params": {
                        "component_id": "modern_house",
                        "roof_type": "flat",
                        "design_sources": {"roof_type": "AI_PLANNED"},
                    },
                },
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "b",
                    "relative_position": {"x": 22, "y": 0, "z": 0},
                    "dimensions": {"width": 6, "depth": 6, "height": 4},
                    "features": ["pavilion"],
                    "params": {
                        "component_id": "garden_pavilion",
                        "roof_type": "gable",
                        "design_sources": {"roof_type": "AI_PLANNED"},
                    },
                },
            ],
        }
        out = _apply_user_explicit_design_sources(
            copy.deepcopy(plan), "建一栋现代住宅，旁边有座歇山顶亭子"
        )
        house = next(c for c in out["components"] if c["params"]["component_id"] == "modern_house")
        pavilion = next(c for c in out["components"] if c["params"]["component_id"] == "garden_pavilion")
        self.assertEqual("flat", house["params"]["roof_type"])
        self.assertEqual("xieshan", pavilion["params"]["roof_type"])
        self.assertEqual("USER_EXPLICIT", pavilion["params"]["design_sources"]["roof_type"])
        self.assertNotEqual("capability_gap", out.get("plan_status"))

    def test_independent_mass_secondary_pavilion_named_omit_binds(self):
        """Acceptance 1/2: independent MASS_SECONDARY pavilion receives named roof omit."""
        from app.services.building_contract import apply_building_contract, design_constraint_hosts
        from app.services.llm_plan_normalization import _apply_user_explicit_design_sources

        text = "亭子不要屋顶。"
        plan = {
            "mode": "build",
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "hall",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 14, "depth": 12, "height": 8},
                    "features": [],
                    "params": {
                        "component_id": "main_hall",
                        "roof_type": "xieshan",
                        "design_sources": {"roof_type": "AI_PLANNED"},
                    },
                },
                {
                    "component_type": "MASS_SECONDARY",
                    "slot_id": "ting",
                    "relative_position": {"x": 24, "y": 0, "z": 0},
                    "dimensions": {"width": 8, "depth": 8, "height": 5},
                    "features": [],
                    "params": {
                        "component_id": "garden_pavilion",
                        "roof_type": "flat",
                        "design_sources": {"roof_type": "AI_PLANNED"},
                    },
                },
            ],
        }
        hosts = {c["params"]["component_id"] for c in design_constraint_hosts(plan["components"])}
        self.assertEqual({"main_hall", "garden_pavilion"}, hosts)
        contracted = apply_building_contract(copy.deepcopy(plan), text, finalize=True)
        bindings = (contracted.get("proportion_hints") or {}).get("building_contract", {}).get("scope_bindings") or {}
        self.assertIn("garden_pavilion", bindings.get("named_pavilion") or [])
        omits = [
            r for r in (contracted.get("proportion_hints") or {}).get("building_contract", {}).get("requirements", [])
            if r.get("property") == "roof_type" and r.get("value") == "none"
        ]
        self.assertTrue(omits, omits)
        self.assertEqual({"garden_pavilion"}, set(omits[0].get("target_components") or []))
        stamped = _apply_user_explicit_design_sources(copy.deepcopy(plan), text)
        pavilion = next(c for c in stamped["components"] if c["params"]["component_id"] == "garden_pavilion")
        hall = next(c for c in stamped["components"] if c["params"]["component_id"] == "main_hall")
        self.assertEqual("none", pavilion["params"]["roof_type"])
        self.assertEqual("xieshan", hall["params"]["roof_type"])

    def test_all_buildings_omit_excludes_attached_plate(self):
        """Acceptance 3: all-building omit covers hosts; plate stays non-host."""
        from app.services.building_contract import apply_building_contract, is_design_constraint_host

        text = "所有建筑都不要屋顶。"
        plan = {
            "mode": "build",
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "hall",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 14, "depth": 12, "height": 8},
                    "features": [],
                    "params": {"component_id": "main_hall", "roof_type": "xieshan"},
                },
                {
                    "component_type": "MASS_SECONDARY",
                    "slot_id": "ting",
                    "relative_position": {"x": 24, "y": 0, "z": 0},
                    "dimensions": {"width": 8, "depth": 8, "height": 5},
                    "features": [],
                    "params": {"component_id": "garden_pavilion", "roof_type": "flat"},
                },
                {
                    "component_type": "MASS_SECONDARY",
                    "slot_id": "deck",
                    "relative_position": {"x": 0, "y": 0, "z": 14},
                    "dimensions": {"width": 6, "depth": 4, "height": 1},
                    "features": [],
                    "params": {
                        "component_id": "front_deck",
                        "host_id": "main_hall",
                        "extrude_mode": "plate",
                        "plate_role": "terrace",
                    },
                },
            ],
        }
        self.assertFalse(is_design_constraint_host(plan["components"][2]))
        out = apply_building_contract(plan, text, finalize=True)
        omits = [
            r for r in (out.get("proportion_hints") or {}).get("building_contract", {}).get("requirements", [])
            if r.get("property") == "roof_type" and r.get("value") == "none"
        ]
        self.assertTrue(omits, omits)
        self.assertEqual({"main_hall", "garden_pavilion"}, set(omits[0].get("target_components") or []))
        self.assertNotIn("front_deck", omits[0].get("target_components") or [])

    def test_building_role_office_binds_named_office_without_id_token(self):
        """Acceptance 4: building_role=office binds named_office when id lacks office."""
        from app.services.building_contract import apply_building_contract

        text = "办公楼不要屋顶。"
        plan = {
            "mode": "build",
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "a",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 12, "depth": 10, "height": 10},
                    "features": [],
                    "params": {
                        "component_id": "building_a",
                        "building_role": "office",
                        "roof_type": "flat",
                    },
                },
            ],
        }
        out = apply_building_contract(plan, text, finalize=True)
        bindings = (out.get("proportion_hints") or {}).get("building_contract", {}).get("scope_bindings") or {}
        self.assertEqual(["building_a"], bindings.get("named_office"))
        omits = [
            r for r in (out.get("proportion_hints") or {}).get("building_contract", {}).get("requirements", [])
            if r.get("property") == "roof_type" and r.get("value") == "none"
        ]
        self.assertTrue(omits, omits)
        self.assertEqual(["building_a"], omits[0].get("target_components"))

    def test_pagoda_typology_named_tower_and_positive_roof_not_silent(self):
        """Acceptance 5/6: pagoda binds as tower; positive xieshan is not silently skipped."""
        from app.services.building_contract import apply_building_contract

        text = "建造主殿和密檐塔，所有建筑都使用歇山顶。"
        plan = {
            "mode": "build",
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "hall",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 14, "depth": 12, "height": 8},
                    "features": [],
                    "params": {"component_id": "main_hall", "roof_type": "xieshan"},
                },
                {
                    "component_type": "STRUCTURE",
                    "slot_id": "pagoda",
                    "relative_position": {"x": 30, "y": 0, "z": 0},
                    "dimensions": {"width": 10, "depth": 10, "height": 24},
                    "features": ["typology:dense_eaves_pagoda"],
                    "params": {
                        "component_id": "dense_pagoda",
                        "typology_id": "dense_eaves_pagoda",
                        "building_role": "pagoda",
                    },
                },
            ],
        }
        out = apply_building_contract(plan, text, finalize=True)
        bindings = (out.get("proportion_hints") or {}).get("building_contract", {}).get("scope_bindings") or {}
        self.assertIn("dense_pagoda", bindings.get("named_tower") or [])
        roofs = [
            r for r in (out.get("proportion_hints") or {}).get("building_contract", {}).get("requirements", [])
            if r.get("property") == "roof_type" and r.get("value") == "xieshan"
        ]
        self.assertTrue(roofs, roofs)
        targets = set(roofs[0].get("target_components") or [])
        self.assertIn("main_hall", targets)
        self.assertIn("dense_pagoda", targets)
        diagnostics = (out.get("proportion_hints") or {}).get("building_contract", {}).get("diagnostics") or []
        self.assertTrue(
            any(d.get("code") == "E_TYPOLOGY_ROOF_UNSUPPORTED" and d.get("component_id") == "dense_pagoda"
                for d in diagnostics),
            diagnostics,
        )
        self.assertEqual("capability_gap", out.get("plan_status"))

    def test_named_binding_order_independent_for_mass_secondary_pavilion(self):
        """Acceptance 10: component order must not change named pavilion binding."""
        from app.services.building_contract import apply_building_contract

        text = "亭子不要屋顶。"
        pavilion = {
            "component_type": "MASS_SECONDARY",
            "slot_id": "ting",
            "relative_position": {"x": 24, "y": 0, "z": 0},
            "dimensions": {"width": 8, "depth": 8, "height": 5},
            "features": [],
            "params": {"component_id": "garden_pavilion", "roof_type": "flat"},
        }
        hall = {
            "component_type": "MASS_MAIN",
            "slot_id": "hall",
            "relative_position": {"x": 0, "y": 0, "z": 0},
            "dimensions": {"width": 14, "depth": 12, "height": 8},
            "features": [],
            "params": {"component_id": "main_hall", "roof_type": "xieshan"},
        }
        forward = apply_building_contract({"mode": "build", "components": [hall, pavilion]}, text, finalize=True)
        backward = apply_building_contract({"mode": "build", "components": [pavilion, hall]}, text, finalize=True)
        fb = (forward.get("proportion_hints") or {}).get("building_contract", {}).get("scope_bindings") or {}
        bb = (backward.get("proportion_hints") or {}).get("building_contract", {}).get("scope_bindings") or {}
        self.assertEqual(fb.get("named_pavilion"), bb.get("named_pavilion"))
        fo = next(
            r for r in (forward.get("proportion_hints") or {}).get("building_contract", {}).get("requirements", [])
            if r.get("property") == "roof_type" and r.get("value") == "none"
        )
        bo = next(
            r for r in (backward.get("proportion_hints") or {}).get("building_contract", {}).get("requirements", [])
            if r.get("property") == "roof_type" and r.get("value") == "none"
        )
        self.assertEqual(set(fo.get("target_components") or []), set(bo.get("target_components") or []))

    def test_dense_eaves_pagoda_omit_roof_is_capability_gap(self):
        """Omit must not succeed on param stamp alone — dense eaves cannot drop eaves/finial."""
        from app.services.building_contract import apply_building_contract, is_design_constraint_host

        text = "建造一座密檐塔，不要屋顶。"
        plan = {
            "mode": "build",
            "components": [{
                "component_type": "STRUCTURE",
                "slot_id": "pagoda",
                "relative_position": {"x": 0, "y": 0, "z": 0},
                "dimensions": {"width": 10, "depth": 10, "height": 24},
                "features": ["typology:dense_eaves_pagoda"],
                "params": {
                    "component_id": "dense_pagoda",
                    "typology_id": "dense_eaves_pagoda",
                    "roof_type": "none",
                },
            }],
        }
        out = apply_building_contract(plan, text, finalize=True)
        self.assertTrue(is_design_constraint_host(out["components"][0]))
        diagnostics = (out.get("proportion_hints") or {}).get("building_contract", {}).get("diagnostics") or []
        self.assertTrue(
            any(d.get("code") == "E_TYPOLOGY_ROOF_UNSUPPORTED" for d in diagnostics),
            diagnostics,
        )
        self.assertEqual("capability_gap", out.get("plan_status"))

    def test_dense_eaves_pagoda_prefilled_xieshan_still_gaps(self):
        from app.services.building_contract import apply_building_contract

        text = "密檐塔使用歇山顶。"
        plan = {
            "mode": "build",
            "components": [{
                "component_type": "STRUCTURE",
                "slot_id": "pagoda",
                "relative_position": {"x": 0, "y": 0, "z": 0},
                "dimensions": {"width": 10, "depth": 10, "height": 24},
                "features": ["typology:dense_eaves_pagoda"],
                "params": {
                    "component_id": "dense_pagoda",
                    "typology_id": "dense_eaves_pagoda",
                    "roof_type": "xieshan",
                },
            }],
        }
        out = apply_building_contract(plan, text, finalize=True)
        diagnostics = (out.get("proportion_hints") or {}).get("building_contract", {}).get("diagnostics") or []
        self.assertTrue(
            any(d.get("code") == "E_TYPOLOGY_ROOF_UNSUPPORTED" for d in diagnostics),
            diagnostics,
        )

    def test_mass_ancillary_without_identity_is_not_design_host(self):
        from app.services.building_contract import is_design_constraint_host, apply_building_contract

        corridor = {
            "component_type": "MASS_ANCILLARY",
            "slot_id": "link",
            "relative_position": {"x": 0, "y": 0, "z": 0},
            "dimensions": {"width": 4, "depth": 12, "height": 4},
            "features": [],
            # component_id embeds neighbor names — must NOT become a design host.
            "params": {"component_id": "corridor_office_workshop", "roof_type": "flat"},
        }
        office = {
            "component_type": "MASS_ANCILLARY",
            "slot_id": "office",
            "relative_position": {"x": 20, "y": 0, "z": 0},
            "dimensions": {"width": 10, "depth": 10, "height": 8},
            "features": [],
            "params": {
                "component_id": "building_a",
                "building_role": "office",
                "roof_type": "gable",
            },
        }
        self.assertFalse(is_design_constraint_host(corridor))
        self.assertTrue(is_design_constraint_host(office))
        out = apply_building_contract(
            {"mode": "build", "components": [corridor, office]},
            "所有建筑都不要屋顶。",
            finalize=True,
        )
        omits = [
            r for r in (out.get("proportion_hints") or {}).get("building_contract", {}).get("requirements", [])
            if r.get("property") == "roof_type" and r.get("value") == "none"
        ]
        self.assertTrue(omits, omits)
        self.assertEqual({"building_a"}, set(omits[0].get("target_components") or []))

    def test_stacked_pagoda_tiers_named_tower_is_one_building(self):
        from app.services.building_contract import apply_building_contract, _unique_named_hosts

        tiers = []
        for i in range(1, 6):
            tiers.append({
                "component_type": "MASS_MAIN",
                "slot_id": "pagoda",
                "relative_position": {"x": 0, "y": (i - 1) * 4, "z": 0},
                "dimensions": {"width": 12 - i, "depth": 12 - i, "height": 4},
                "features": [],
                "params": {"component_id": f"pagoda_tier_{i}", "roof_type": "flat"},
            })
        collapsed = _unique_named_hosts(tiers)
        self.assertIsNotNone(collapsed)
        self.assertEqual(5, len(collapsed))
        out = apply_building_contract(
            {"mode": "build", "components": tiers},
            "塔楼不要屋顶。",
            finalize=True,
        )
        bindings = (out.get("proportion_hints") or {}).get("building_contract", {}).get("scope_bindings") or {}
        self.assertEqual(
            {f"pagoda_tier_{i}" for i in range(1, 6)},
            set(bindings.get("named_tower") or []),
        )
        omits = [
            r for r in (out.get("proportion_hints") or {}).get("building_contract", {}).get("requirements", [])
            if r.get("property") == "roof_type" and r.get("value") == "none"
        ]
        self.assertTrue(omits, omits)
        self.assertEqual(
            {f"pagoda_tier_{i}" for i in range(1, 6)},
            set(omits[0].get("target_components") or []),
        )
        self.assertNotEqual("unresolved_binding", omits[0].get("status"))

    def test_named_wing_plural_and_west_side_through_normalization(self):
        from copy import deepcopy
        from app.services.llm_plan_normalization import _apply_user_explicit_design_sources
        from app.services.building_contract import _unique_named_hosts, apply_building_contract

        def wings():
            return [
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "w",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": 9, "depth": 13, "height": 6},
                    "features": [],
                    "params": {"component_id": "townhall_west_wing", "roof_type": "gable"},
                },
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "e",
                    "relative_position": {"x": 20, "y": 0, "z": 0},
                    "dimensions": {"width": 9, "depth": 13, "height": 6},
                    "features": [],
                    "params": {"component_id": "townhall_east_wing", "roof_type": "gable"},
                },
            ]

        sample = wings()
        # Normalization must pass named_key=wing so plural hosts stay coherent.
        self.assertIsNotNone(_unique_named_hosts(sample, named_key="wing"))
        self.assertIsNone(_unique_named_hosts(sample))

        all_text = "\u6240\u6709\u53a2\u623f\u90fd\u4e0d\u8981\u5c4b\u9876"  # 所有厢房都不要屋顶
        west_text = "\u897f\u53a2\u623f\u4e0d\u8981\u5c4b\u9876"  # 西厢房不要屋顶
        all_out = _apply_user_explicit_design_sources(
            {"mode": "build", "components": deepcopy(wings())},
            all_text,
        )
        self.assertEqual(
            "none",
            next(c for c in all_out["components"] if c["params"]["component_id"] == "townhall_west_wing")
            ["params"]["roof_type"],
        )
        self.assertEqual(
            "none",
            next(c for c in all_out["components"] if c["params"]["component_id"] == "townhall_east_wing")
            ["params"]["roof_type"],
        )

        west_out = _apply_user_explicit_design_sources(
            {"mode": "build", "components": deepcopy(wings())},
            west_text,
        )
        self.assertEqual(
            "none",
            next(c for c in west_out["components"] if c["params"]["component_id"] == "townhall_west_wing")
            ["params"]["roof_type"],
        )
        self.assertEqual(
            "gable",
            next(c for c in west_out["components"] if c["params"]["component_id"] == "townhall_east_wing")
            ["params"]["roof_type"],
        )

        contract = apply_building_contract(
            {"mode": "build", "components": deepcopy(wings())},
            west_text,
            finalize=True,
        )
        roof_reqs = [
            r for r in contract["proportion_hints"]["building_contract"]["requirements"]
            if r.get("property") == "roof_type" and r.get("value") == "none"
        ]
        self.assertTrue(roof_reqs)
        self.assertEqual(["townhall_west_wing"], roof_reqs[0].get("target_components"))

        from app.services.building_contract import (
            _filter_hosts_by_wing_side,
            _span_clause_window,
            _wing_sides_from_text,
        )

        joint = "\u897f\u53a2\u623f\u548c\u4e1c\u53a2\u623f\u90fd\u4e0d\u8981\u5c4b\u9876"  # 西厢房和东厢房都不要屋顶
        self.assertEqual(["west", "east"], _wing_sides_from_text(joint))
        joint_out = _apply_user_explicit_design_sources(
            {"mode": "build", "components": deepcopy(wings())},
            joint,
        )
        self.assertEqual(
            "none",
            next(c for c in joint_out["components"] if c["params"]["component_id"] == "townhall_west_wing")
            ["params"]["roof_type"],
        )
        self.assertEqual(
            "none",
            next(c for c in joint_out["components"] if c["params"]["component_id"] == "townhall_east_wing")
            ["params"]["roof_type"],
        )

        comma = "\u897f\u53a2\u623f\u4e0d\u8981\u5c4b\u9876\uff0c\u4e1c\u53a2\u623f\u4e0d\u8981\u5c4b\u9876"  # 西厢房不要屋顶，东厢房不要屋顶
        west_span = (0, len("\u897f\u53a2\u623f\u4e0d\u8981\u5c4b\u9876"))
        west_clause = _span_clause_window(comma, west_span[0], west_span[1])
        self.assertIn("\u897f", west_clause)
        self.assertNotIn("\u4e1c", west_clause)
        sided = _filter_hosts_by_wing_side(deepcopy(wings()), west_clause)
        self.assertIsNotNone(sided)
        self.assertEqual(1, len(sided))
        self.assertEqual("townhall_west_wing", sided[0]["params"]["component_id"])


if __name__ == "__main__":
    unittest.main()
