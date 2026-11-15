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
        ("悉尼歌剧院", {"MASS_MAIN", "ROOF", "ROOF_STRUCTURE"}, "CLUSTER"),
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
