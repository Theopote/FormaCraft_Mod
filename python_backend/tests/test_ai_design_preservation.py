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
        self.assertTrue(all(r.get("scope_source") == "INFERRED" or r.get("status") == "unresolved" for r in reqs))

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
        """Unresolved local window omit must not escalate to every MASS."""
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
        self.assertEqual("regular", house["params"]["window_style"])
        self.assertTrue(any(
            c.get("params", {}).get("component_id") == "facade_house"
            for c in out["components"]
        ))
        self.assertEqual("capability_gap", out.get("plan_status"))
        self.assertEqual("E_OMIT_SCOPE_AMBIGUOUS", (out.get("capability_gap") or {}).get("code"))

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
        self.assertEqual("flat", house["params"]["roof_type"])
        self.assertTrue(any(
            c.get("params", {}).get("component_id") == "roof_house"
            for c in out["components"]
        ))
        self.assertEqual("capability_gap", out.get("plan_status"))
        self.assertEqual("E_OMIT_SCOPE_AMBIGUOUS", (out.get("capability_gap") or {}).get("code"))

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


if __name__ == "__main__":
    unittest.main()
