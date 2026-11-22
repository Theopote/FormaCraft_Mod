from copy import deepcopy

import pytest

from app.models.building_profile import BuildingProfile
from app.services.building_research_agent import finalize_profile_minecraft_strategy, resolve_landmark_module_for_intent
from app.services.building_plan_stage import research_landmark_override_block
from app.services.landmark_design_policy import reject_fixed_landmark_plan


@pytest.mark.parametrize(
    "name",
    [
        "Eiffel Tower",
        "天坛",
        "悉尼歌剧院",
        "生成金门大桥",
    ],
)
def test_landmark_name_is_evidence_not_executor(name):
    """Named landmarks clear fixed executors; no name-bound component/skeleton kit."""
    profile = finalize_profile_minecraft_strategy(BuildingProfile(query=name), name)
    strategy = profile.minecraft_strategy
    assert strategy.model_designed_landmark
    assert strategy.landmark_module is None
    assert strategy.structural_typology is None
    assert resolve_landmark_module_for_intent(name) is None
    override = research_landmark_override_block(profile)
    assert "optional evidence" in override.lower() or "Design this landmark yourself" in override or (
        "Open-world AI design" in override
    )
    assert "typology:" not in override or "unless the user explicitly requested" in override
    # No forced name-bound kits when research did not provide components.
    assert strategy.recommended_components_source != "hint"
    if not strategy.recommended_components:
        assert strategy.recommended_components_source in ("default", None)


def test_explicit_preset_compatibility():
    assert resolve_landmark_module_for_intent("preset:eiffel_tower") == "eiffel_tower"
    profile = finalize_profile_minecraft_strategy(BuildingProfile(query="Eiffel Tower"), "preset:eiffel_tower")
    assert not profile.minecraft_strategy.model_designed_landmark
    assert (profile.minecraft_strategy.landmark_module == "eiffel_tower"
            or profile.minecraft_strategy.reference_landmark == "eiffel_tower")


def test_unknown_landmark_keeps_research_structure_semantics():
    """Open-world research must not be overwritten by residential defaults or name kits."""
    profile = BuildingProfile(
        query="建造千禧桥",
        minecraft_strategy={
            "skeleton_type": "SPAN_SUSPENSION",
            "recommended_components": ["BRIDGE", "TOWER", "BRIDGE_CONNECTOR", "PAVING"],
            "recommended_components_source": "research",
            "notes": "Research: blade piers, shallow suspension deck, twin Y-towers.",
        },
        structure={
            "distinguishing_features": [
                "shallow suspension deck",
                "twin Y-shaped towers",
                "blade piers in river",
            ],
        },
        request_classification={
            "is_specific_real_building": True,
            "building_name_normalized": "millennium_bridge",
            "confidence": 0.9,
            "reasoning_hint": "named real landmark outside static hint table",
            "source": "rules",
        },
    )
    result = finalize_profile_minecraft_strategy(profile, "建造千禧桥")
    strategy = result.minecraft_strategy
    assert strategy.model_designed_landmark
    assert strategy.structural_typology is None
    assert strategy.landmark_module is None
    assert strategy.skeleton_type == "SPAN_SUSPENSION"
    assert {"BRIDGE", "TOWER", "BRIDGE_CONNECTOR"} <= set(strategy.recommended_components)
    assert "ROOF" not in strategy.recommended_components
    assert "ENTRANCE" not in strategy.recommended_components
    assert "blade piers" in (strategy.notes or "")


def test_unknown_landmark_without_research_stays_open():
    profile = BuildingProfile(
        query="建造未收录奇观塔",
        request_classification={
            "is_specific_real_building": True,
            "building_name_normalized": "unknown_wonder_tower",
            "confidence": 0.85,
            "reasoning_hint": "named real landmark with no research structure yet",
            "source": "rules",
        },
    )
    result = finalize_profile_minecraft_strategy(profile, "建造未收录奇观塔")
    strategy = result.minecraft_strategy
    assert strategy.model_designed_landmark
    assert strategy.recommended_components == []
    assert strategy.landmark_module is None
    assert strategy.structural_typology is None
    assert "residential" in (strategy.notes or "").lower() or "MASS/ROOF/ENTRANCE" in (strategy.notes or "")


def test_unknown_classical_research_components_are_not_cleared():
    """Ordinary-looking research comps must survive when provenance is research."""
    profile = BuildingProfile(
        query="建造未收录古典纪念堂",
        minecraft_strategy={
            "skeleton_type": "COMPOUND",
            "recommended_components": ["MASS_MAIN", "ROOF", "TERRACE"],
            "recommended_components_source": "research",
            "notes": "Research: raised podium terrace, gabled cella, axial stair.",
        },
        request_classification={
            "is_specific_real_building": True,
            "building_name_normalized": "unknown_classical_memorial",
            "confidence": 0.9,
            "reasoning_hint": "named real landmark outside static hint table",
            "source": "rules",
        },
    )
    result = finalize_profile_minecraft_strategy(profile, "建造未收录古典纪念堂")
    strategy = result.minecraft_strategy
    assert strategy.model_designed_landmark
    assert strategy.recommended_components == ["MASS_MAIN", "ROOF", "TERRACE"]
    assert strategy.recommended_components_source == "research"


def test_weak_residential_research_is_not_replaced_by_name_kit():
    """Weak research stays open — no Golden Gate hint table overwrite."""
    profile = BuildingProfile(
        query="建造金门大桥",
        minecraft_strategy={
            "skeleton_type": "COMPOUND",
            "recommended_components": ["MASS_MAIN", "ROOF", "FACADE_WINDOWS", "ENTRANCE"],
            "recommended_components_source": "research",
            "notes": "Generic civic massing with windows and a main entrance.",
        },
        request_classification={
            "is_specific_real_building": True,
            "building_name_normalized": "golden_gate_bridge",
            "confidence": 0.95,
            "reasoning_hint": "named landmark",
            "source": "rules",
        },
    )
    result = finalize_profile_minecraft_strategy(profile, "建造金门大桥")
    strategy = result.minecraft_strategy
    assert strategy.model_designed_landmark
    assert strategy.structural_typology is None
    assert strategy.landmark_module is None
    # Research residential comps are kept as evidence (AI may reinterpret in Stage P),
    # but code must NOT inject a bridge kit from a name table.
    assert strategy.recommended_components_source != "hint"
    assert "TOWER_BASE" not in strategy.recommended_components
    assert not (
        strategy.skeleton_type == "SPAN_SUSPENSION"
        and {"BRIDGE", "TOWER", "BRIDGE_CONNECTOR"} <= set(strategy.recommended_components)
        and strategy.recommended_components_source == "hint"
    )


def test_dual_research_plans_are_not_forced_identical():
    """Two different research interpretations of the same landmark stay distinct."""
    text = "建造埃菲尔铁塔"
    lattice = finalize_profile_minecraft_strategy(
        BuildingProfile(
            query=text,
            minecraft_strategy={
                "skeleton_type": "VERTICAL_TAPER",
                "recommended_components": ["TOWER", "STRUCTURE", "PAVING"],
                "recommended_components_source": "research",
                "notes": "Four tapering lattice legs with platforms.",
            },
            request_classification={
                "is_specific_real_building": True,
                "building_name_normalized": "eiffel_tower",
                "confidence": 0.95,
                "source": "rules",
            },
        ),
        text,
    )
    bold = finalize_profile_minecraft_strategy(
        BuildingProfile(
            query=text,
            minecraft_strategy={
                "skeleton_type": "COMPOUND",
                "recommended_components": ["MASS_MAIN", "TOWER", "DECOR_DETAIL"],
                "recommended_components_source": "research",
                "notes": "Bold geometric simplification of iron lattice silhouette.",
            },
            request_classification={
                "is_specific_real_building": True,
                "building_name_normalized": "eiffel_tower",
                "confidence": 0.95,
                "source": "rules",
            },
        ),
        text,
    )
    assert lattice.minecraft_strategy.landmark_module is None
    assert bold.minecraft_strategy.landmark_module is None
    assert lattice.minecraft_strategy.recommended_components != bold.minecraft_strategy.recommended_components
    assert lattice.minecraft_strategy.skeleton_type != bold.minecraft_strategy.skeleton_type


def test_model_designed_stage_p_hides_fixed_module_route():
    from app.services.building_plan_stage import build_plan_stage_user_block

    profile = finalize_profile_minecraft_strategy(
        BuildingProfile(query="建造千禧桥"),
        "建造千禧桥",
    )
    assert profile.minecraft_strategy.model_designed_landmark
    block = build_plan_stage_user_block(profile, "建造千禧桥")
    assert "Directly plannable component_type values" in block
    assert "optional evidence" in block.lower()
    assert "- MODULE [" not in block
    assert "must" not in block.lower() or "USER REQUEST is authoritative" in block


def test_fbd_negation_keeps_only_ordinary_assembly_contract():
    from app.services.generation_capability import planning_component_exposure

    profile = BuildingProfile(query="古典宫殿")
    profile.minecraft_strategy.model_designed_landmark = True
    exposure = planning_component_exposure(
        profile,
        user_request="建造一座大型古典宫殿，不要使用 FBD，使用常规构件。",
    )
    assert not exposure["context"]["explicit_fbd"]
    assert "params.assembly.ops" in exposure["conditional"]["ASSEMBLY"]["requires"]
    assert "FBD" not in exposure["conditional"]["ASSEMBLY"]["requires"]
    assert "- MODULE" not in str(exposure["conditional"])


def test_preset_mention_under_model_designed_still_hides_module():
    from app.services.building_research_agent import resolve_landmark_module_for_intent
    from app.services.generation_capability import planning_component_exposure

    text = "建造天坛，不要使用 preset:temple_of_heaven"
    assert resolve_landmark_module_for_intent(text) is None
    profile = finalize_profile_minecraft_strategy(BuildingProfile(query="天坛"), text)
    assert profile.minecraft_strategy.model_designed_landmark
    exposure = planning_component_exposure(profile, user_request=text)
    assert not exposure["context"]["preset_module"]
    assert "MODULE" not in exposure["conditional"]


def test_llm_cannot_self_declare_user_component_source():
    from app.models.building_profile import profile_from_llm_dict

    profile = profile_from_llm_dict(
        {
            "query": "纪念堂",
            "minecraft_strategy": {
                "recommended_components": ["MASS_MAIN", "ROOF", "TERRACE"],
                "recommended_components_source": "user",
            },
        },
        fallback_query="纪念堂",
    )
    assert profile.minecraft_strategy.recommended_components_source == "research"


def test_two_model_designs_are_not_replaced_by_a_shared_template():
    profile = BuildingProfile(query="Eiffel Tower")
    profile.minecraft_strategy.model_designed_landmark = True
    for width in (12, 23):
        plan = {"components": [{"component_type": "MASS_MAIN", "dimensions": {"width": width}}]}
        original = deepcopy(plan)
        assert reject_fixed_landmark_plan(plan, profile) is None
        assert plan == original


def test_normalization_preserves_different_model_massing():
    from app.services.llm_plan_normalization import _normalize_llm_plan_output

    profile = BuildingProfile(query="landmark")
    profile.minecraft_strategy.model_designed_landmark = True
    for width in (12, 23):
        plan = {"mode": "build", "style_profile": "DEFAULT", "anchor": {"x": 0, "y": 64, "z": 0},
                "components": [{"component_type": "MASS_MAIN", "relative_position": {"x": 0, "y": 0, "z": 0},
                                "dimensions": {"width": width, "depth": 8, "height": 12},
                                "features": [], "params": {"component_id": "model_mass", "plan_type": "none"}}]}
        result = _normalize_llm_plan_output(plan, None, building_profile=profile)
        assert result.get("plan_status") != "capability_gap"
        mass = next(c for c in result["components"] if c["params"].get("component_id") == "model_mass")
        assert mass["dimensions"]["width"] == width


@pytest.mark.parametrize("user_message", [
    "设计原创圆厅",
    "设计原创圆厅，不要使用 preset:temple_of_heaven",
    "preset:eiffel_tower",
])
def test_unrequested_landmark_module_cannot_be_converted(user_message):
    from types import SimpleNamespace
    from app.services.llm_plan_normalization import _normalize_llm_plan_output

    body = {"component_type": "MASS_MAIN", "relative_position": {"x": 0, "y": 0, "z": 0},
            "dimensions": {"width": 12, "depth": 8, "height": 10}, "params": {"component_id": "body"}}
    fixed = {"component_type": "MODULE", "features": ["landmark:temple_of_heaven"]}
    req = SimpleNamespace(userMessage=user_message, requestText="preset:temple_of_heaven")
    result = _normalize_llm_plan_output({"mode": "build", "components": [body, fixed]}, req)
    assert result["capability_gap"]["code"] == "FIXED_LANDMARK_PRESET_DISALLOWED"
    assert result["capability_gap"]["path"] == "components[1]"
    assert result["components"] == [body]


def test_matching_explicit_preset_can_use_landmark_module():
    from app.services.landmark_design_policy import reject_unrequested_fixed_routes

    plan = {"components": [{"component_type": "MODULE", "features": ["landmark:eiffel_tower"]}]}
    assert reject_unrequested_fixed_routes(plan, "preset:eiffel_tower") is None
    assert reject_unrequested_fixed_routes(plan, "设计原创塔楼") is not None
    assert reject_unrequested_fixed_routes(plan, "不要使用 preset:eiffel_tower") is not None


def test_normalization_rejects_fixed_plan_before_typology_replacement():
    from app.services.llm_plan_normalization import _normalize_llm_plan_output

    profile = BuildingProfile(query="temple")
    profile.minecraft_strategy.model_designed_landmark = True
    result = _normalize_llm_plan_output({"components": [{"component_type": "MODULE",
        "features": ["landmark:temple_of_heaven"]}]}, None, building_profile=profile)
    assert result["capability_gap"]["code"] == "FIXED_LANDMARK_PRESET_DISALLOWED"


@pytest.mark.parametrize("component", [{"component_type": "MODULE"},
    {"component_type": "STRUCTURE", "features": ["typology:radial_terrace_hall"]}])
def test_fixed_executor_becomes_explicit_gap(component):
    profile = BuildingProfile(query="landmark")
    profile.minecraft_strategy.model_designed_landmark = True
    result = reject_fixed_landmark_plan({"components": [component]}, profile)
    assert result["plan_status"] == "capability_gap"
    assert result["components"] == []


def test_invalid_sydney_opera_plan_does_not_swap_to_generic_template():
    """Open-world landmarks may repair or gap — never silent MASS_MAIN kits."""
    from types import SimpleNamespace
    from unittest.mock import patch

    from app.services.llm_plan_normalization import _normalize_llm_plan_output
    from app.services.llm_plan_repair import repair_llm_plan_validation

    profile = finalize_profile_minecraft_strategy(
        BuildingProfile(query="悉尼歌剧院"),
        "建造悉尼歌剧院",
    )
    assert profile.minecraft_strategy.model_designed_landmark

    bad_plan = {
        "mode": "build",
        "style_profile": "DEFAULT",
    }
    req = SimpleNamespace(
        userMessage="建造悉尼歌剧院",
        selection=None,
        brushSelection=None,
        world=None,
        outline=None,
        symmetry=None,
        player=SimpleNamespace(pos=SimpleNamespace(x=10, y=64, z=20), facing="EAST"),
    )

    def normalize_fn(plan, build_req, **_kwargs):
        return _normalize_llm_plan_output(plan, build_req, building_profile=profile)

    with patch("app.services.llm_plan_repair.LLMPLAN_VALIDATION_REPAIR_LLM", False):
        with patch(
            "app.services.llm_plan_repair.build_minimal_plan_from_profile"
        ) as fallback:
            with patch(
                "app.services.request_intent.scaffold_plan_for_intent"
            ) as scaffold:
                out = repair_llm_plan_validation(
                    bad_plan,
                    req,
                    user_text="建造悉尼歌剧院",
                    building_profile=profile,
                    normalize_fn=normalize_fn,
                )
                fallback.assert_not_called()
                scaffold.assert_not_called()
    assert out.get("plan_status") == "capability_gap"
    assert out.get("capability_gap", {}).get("code") == "E_LLMPLAN_VALIDATION"


def test_open_world_creative_plan_also_skips_house_kit_fallback():
    """Non-landmark creative requests also preserve design — no silent MASS_MAIN swap."""
    from types import SimpleNamespace
    from unittest.mock import patch

    from app.services.llm_plan_repair import repair_llm_plan_validation

    profile = BuildingProfile(
        query="漂浮峡谷图书馆",
        minecraft_strategy={
            "model_designed_landmark": False,
            "landmark_module": None,
            "structural_typology": None,
            "recommended_components": [],
        },
    )
    bad = {"mode": "build", "style_profile": "DEFAULT"}
    req = SimpleNamespace(
        player=SimpleNamespace(pos=SimpleNamespace(x=1, y=64, z=2), facing="NORTH"),
        symmetry=None,
    )
    with patch("app.services.llm_plan_repair.LLMPLAN_VALIDATION_REPAIR_LLM", False):
        with patch("app.services.llm_plan_repair.build_minimal_plan_from_profile") as fallback:
            out = repair_llm_plan_validation(
                bad,
                req,
                user_text="建造一座漂浮在峡谷上方的未来主义图书馆",
                building_profile=profile,
                normalize_fn=lambda plan, _req: plan,
            )
            fallback.assert_not_called()
    assert out.get("plan_status") == "capability_gap"


def test_pagoda_model_designed_repair_prompt_forbids_typology_id():
    import json
    from types import SimpleNamespace
    from unittest.mock import patch

    from app.services import llm_plan_repair as repair

    profile = finalize_profile_minecraft_strategy(
        BuildingProfile(query="佛塔"),
        "建造一座七层重檐佛塔",
    )
    assert profile.minecraft_strategy.model_designed_landmark

    bad = {
        "mode": "build",
        "style_profile": "DEFAULT",
        "anchor": {"x": 1, "y": 64, "z": 2},
        "global_constraints": {"facing": "SOUTH", "symmetry": "NONE", "terrain_strategy": "ADAPTIVE"},
        "layout": {"skeleton_type": "VERTICAL_STACK", "path_based": False, "slots": []},
        "components": [],
        "genome": {"genomeVersion": "1.0", "symmetry": {"type": "none", "order": None, "mirror": None}},
    }
    valid = dict(bad)
    valid["components"] = [
        {
            "component_type": "TOWER",
            "relative_position": {"x": 0, "y": 0, "z": 0},
            "dimensions": {"width": 12, "depth": 12, "height": 40},
            "features": [],
            "params": {"component_id": "pagoda_main", "floor_count": 7},
        }
    ]
    req = SimpleNamespace(
        player=SimpleNamespace(pos=SimpleNamespace(x=1, y=64, z=2), facing="SOUTH"),
        symmetry=None,
    )
    call = patch(
        "app.models.llm_plan_json_schema.call_chat_with_llm_plan_response_formats"
    ).start()
    try:
        call.return_value = (
            SimpleNamespace(
                choices=[
                    SimpleNamespace(
                        message=SimpleNamespace(content=json.dumps(valid))
                    )
                ]
            ),
            "json_object",
        )
        with patch.object(repair, "LLMPLAN_VALIDATION_REPAIR_LLM", True):
            out = repair.repair_llm_plan_validation(
                bad,
                req,
                user_text="建造一座七层重檐佛塔",
                building_profile=profile,
                normalize_fn=lambda plan, _req: plan,
                client=object(),
                model="test-model",
                messages=[{"role": "user", "content": "建造一座七层重檐佛塔"}],
                call_with_timeout=lambda fn, timeout: fn(),
            )
        assert call.call_count >= 1
        prompt = call.call_args.kwargs["messages"][-1]["content"]
        assert "dense_eaves_pagoda" not in prompt
        assert "Do NOT emit params.typology_id" in prompt or "open-world" in prompt.lower()
        assert out.get("plan_status") != "capability_gap"
    finally:
        patch.stopall()
