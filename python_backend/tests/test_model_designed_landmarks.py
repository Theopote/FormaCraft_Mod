from copy import deepcopy

import pytest

from app.models.building_profile import BuildingProfile
from app.services.building_research_agent import finalize_profile_minecraft_strategy, resolve_landmark_module_for_intent
from app.services.building_plan_stage import research_landmark_override_block
from app.services.landmark_design_policy import reject_fixed_landmark_plan


@pytest.mark.parametrize(
    "name,expected_components,skeleton",
    [
        ("Eiffel Tower", {"TOWER", "STRUCTURE"}, "VERTICAL_TAPER"),
        ("天坛", {"MASS_MAIN", "TERRACE", "ROOF"}, "RADIAL_RING"),
        ("悉尼歌剧院", {"MASS_MAIN", "ROOF", "ROOF_STRUCTURE"}, "COMPOUND"),
        ("生成金门大桥", {"BRIDGE", "TOWER", "BRIDGE_CONNECTOR"}, "SPAN_SUSPENSION"),
    ],
)
def test_landmark_name_is_evidence_not_executor(name, expected_components, skeleton):
    profile = finalize_profile_minecraft_strategy(BuildingProfile(query=name), name)
    strategy = profile.minecraft_strategy
    assert strategy.model_designed_landmark
    assert strategy.landmark_module is None
    assert strategy.structural_typology is None
    assert resolve_landmark_module_for_intent(name) is None
    assert "Design this landmark yourself" in research_landmark_override_block(profile)
    assert strategy.skeleton_type == skeleton
    assert expected_components <= set(strategy.recommended_components)


def test_explicit_preset_compatibility():
    assert resolve_landmark_module_for_intent("preset:eiffel_tower") == "eiffel_tower"
    profile = finalize_profile_minecraft_strategy(BuildingProfile(query="Eiffel Tower"), "preset:eiffel_tower")
    assert not profile.minecraft_strategy.model_designed_landmark
    assert (profile.minecraft_strategy.landmark_module == "eiffel_tower"
            or profile.minecraft_strategy.reference_landmark == "eiffel_tower")


def test_unknown_landmark_keeps_research_structure_semantics():
    """Open-world research must not be overwritten by the residential default table."""
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


def test_model_designed_stage_p_hides_fixed_module_route():
    from app.services.building_plan_stage import build_plan_stage_user_block

    profile = finalize_profile_minecraft_strategy(
        BuildingProfile(query="建造千禧桥"),
        "建造千禧桥",
    )
    assert profile.minecraft_strategy.model_designed_landmark
    block = build_plan_stage_user_block(profile, "建造千禧桥")
    assert "Directly plannable component_type values" in block
    assert "BRIDGE" in block
    # Fixed MODULE route must not be offered for model-designed landmarks.
    assert "- MODULE [" not in block
    assert "typology:* and fixed landmark executors are disallowed" in block or (
        "parametric structure_type only" in block
    )


def test_fbd_negation_does_not_expose_assembly():
    from app.services.generation_capability import planning_component_exposure

    profile = BuildingProfile(query="古典宫殿")
    profile.minecraft_strategy.model_designed_landmark = True
    exposure = planning_component_exposure(
        profile,
        user_request="建造一座大型古典宫殿，不要使用 FBD，使用常规构件。",
    )
    assert not exposure["context"]["explicit_fbd"]
    assert "ASSEMBLY" not in exposure["conditional"]
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


def test_weak_residential_research_does_not_override_golden_gate_hints():
    """Research source alone is not enough when content lacks bridge structure."""
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
    assert strategy.skeleton_type == "SPAN_SUSPENSION"
    assert {"BRIDGE", "TOWER", "BRIDGE_CONNECTOR"} <= set(strategy.recommended_components)
    assert strategy.recommended_components_source == "hint"
    assert "ROOF" not in strategy.recommended_components or "BRIDGE" in strategy.recommended_components
    assert "ENTRANCE" not in strategy.recommended_components


def test_invalid_sydney_opera_plan_does_not_swap_to_generic_template():
    """Model-designed landmarks may repair or gap — never silent MASS_MAIN kits."""
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
        # Missing components / dimensions → validation failure
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
        assert "typology_id" not in prompt or "Do NOT emit params.typology_id" in prompt
        assert "dense_eaves_pagoda" not in prompt
        assert "model-designed" in prompt.lower() or "Do NOT emit params.typology_id" in prompt
        assert out.get("plan_status") != "capability_gap"
    finally:
        patch.stopall()
