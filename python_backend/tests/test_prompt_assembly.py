"""Prompt ownership: backend owns system prompt; spatial constraints injected."""

import pytest

from app.models.request import (
    BuildRequest,
    OutlineShape,
    PathConstraint,
    PlayerInfo,
    ProtectedZone,
    Selection,
    SymmetryConstraint,
    WorldContext,
    Vec3i,
)
from app.services.prompt_assembly import (
    format_spatial_constraints,
    resolve_llm_plan_prompts,
)
from app.services.llm_plan_system_prompt import build_llm_plan_system_prompt


def _req(**kwargs) -> BuildRequest:
    base = dict(
        player=PlayerInfo(name="p", pos=Vec3i(x=1, y=64, z=2), facing="NORTH"),
        world=WorldContext(dimension="minecraft:overworld"),
        requestText="",
    )
    base.update(kwargs)
    return BuildRequest(**base)


def test_user_message_with_marker_not_truncated():
    marker_text = "please include USER REQUEST: as literal words in the plan notes"
    system, user = resolve_llm_plan_prompts(
        _req(userMessage=marker_text, requestText="ignored assembled blob USER REQUEST: truncated"),
        default_system=build_llm_plan_system_prompt(None),
    )
    assert "truncated" not in user
    assert marker_text in user
    assert "Formacraft Core" in system
    assert "ASSEMBLY GEOMETRY INDEX" in system
    assert "LlmPlan JSON shape" in system


def test_client_system_and_user_prompt_ignored():
    system, user = resolve_llm_plan_prompts(
        _req(systemPrompt="INJECTED_SYS", userPrompt="INJECTED_USR", userMessage="real intent"),
        default_system="DEFAULT_SYS",
    )
    assert system == "DEFAULT_SYS"
    assert "INJECTED_SYS" not in system
    assert "INJECTED_USR" not in user
    assert "real intent" in user


def test_ordinary_geometry_prompt_preserves_contracts_without_residential_defaults(monkeypatch):
    from app.services import ai_planner

    monkeypatch.setattr(ai_planner, "_LLMPLAN_INJECT_CULTURE_RAG", False)
    req = _req(userMessage="设计一座逆光悬庭，不要屋顶")
    system = build_llm_plan_system_prompt(req)
    context = ai_planner._llm_plan_context_block(req)
    for token in ("BEZIER_SURFACE", "REVOLVE_SURFACE", "LOFT_SURFACE", "min_corner", "ComponentId.port"):
        assert token in system
    assert "ports=[" not in system
    assert "- presets:" not in system
    assert "params.host_id" in context
    assert "roof_type='none'" in context
    assert "floor_height=4" not in context
    assert "normal residential walls" not in context


def test_stair_guidance_preserves_build_component_coordinate_conventions(monkeypatch):
    from app.services import ai_planner

    monkeypatch.setattr(ai_planner, "_LLMPLAN_INJECT_CULTURE_RAG", False)
    req = _req(userMessage="设计两栋带楼梯并用空中连廊相连的建筑")
    system = build_llm_plan_system_prompt(req)
    context = ai_planner._llm_plan_context_block(req)
    assert "Slot anchors are offsets from the plan anchor" in system
    assert "Without a slot, positions are relative to the plan anchor" in system
    assert "defaults to footprint center" in system
    assert "Endpoints are local to relative_position" in context
    assert "share their host's top-level slot_id" in context
    assert "anchor_mode='min_corner'" not in context


def test_spatial_constraints_appended_to_user_prompt():
    req = _req(
        userMessage="build a house",
        selection=Selection(min=Vec3i(x=0, y=60, z=0), max=Vec3i(x=10, y=70, z=10)),
        outline=OutlineShape(
            shapeType="circle",
            center=Vec3i(x=5, y=64, z=5),
            radius=8,
            minY=60,
            maxY=80,
            vertices=None,
        ),
        protectedZones=[
            ProtectedZone(min=Vec3i(x=1, y=60, z=1), max=Vec3i(x=2, y=62, z=2)),
        ],
        path=PathConstraint(
            nodes=[Vec3i(x=0, y=64, z=0), Vec3i(x=5, y=64, z=5)],
            radius=2,
        ),
        promptMode="BUILD",
    )
    system, user = resolve_llm_plan_prompts(req, default_system="SYS")
    assert system == "SYS"
    assert user.startswith("build a house")
    assert "REQUEST CONTEXT (HARD" in user
    assert "Selection AABB" in user
    assert "Outline (HARD): circle" in user
    assert "ProtectedZones" in user
    assert "Path corridor" in user
    assert "Facing: NORTH" in user


def test_format_spatial_constraints_empty_without_extras():
    text = format_spatial_constraints(_req())
    assert "Anchor/player pos" in text
    assert "Facing: NORTH" in text


def test_library_and_memory_summaries_appended():
    system, user = resolve_llm_plan_prompts(
        _req(
            userMessage="reuse my door component",
            componentCatalogSummary="Player component library:\n- DOOR: oak_door (id=oak_door)",
            componentGroupSummary="group: porch_kit components=3",
            nearbyMemorySummary="Nearby building memory:\n- name=Cabin uuid=abc",
        ),
        default_system="SYS",
    )
    assert system == "SYS"
    assert "LIBRARY / MEMORY CONTEXT" in user
    assert "oak_door" in user
    assert "porch_kit" in user
    assert "Cabin" in user


def test_polygon_outline_includes_bbox_and_full_vertex_count():
    verts = [Vec3i(x=i, y=64, z=i * 2) for i in range(12)]
    text = format_spatial_constraints(
        _req(
            outline=OutlineShape(
                shapeType="polygon",
                vertices=verts,
                minY=60,
                maxY=80,
            )
        )
    )
    assert "vertices=12" in text
    assert "bboxXZ=" in text
    assert "(11,64,22)" in text  # last vertex within raised 32-cap
    assert "omitted" not in text


def test_symmetry_constraint_is_injected_as_hard_constraint():
    text = format_spatial_constraints(
        _req(symmetry=SymmetryConstraint(mode="MIRROR_X"))
    )
    assert "Symmetry (HARD)" in text
    assert "left/right symmetry" in text
    assert "global_constraints.symmetry=MIRROR_X" in text


def test_symmetry_none_produces_no_constraint_line():
    text = format_spatial_constraints(_req(symmetry=SymmetryConstraint(mode="NONE")))
    assert "Symmetry (HARD)" not in text
    # An absent constraint must not create an empty constraint block.
    assert format_spatial_constraints(_req()) and "Symmetry" not in format_spatial_constraints(_req())


def test_custom_axis_symmetry_reports_axis_points():
    text = format_spatial_constraints(
        _req(
            symmetry=SymmetryConstraint(
                mode="CUSTOM_AXIS",
                axisA=Vec3i(x=1, y=64, z=2),
                axisB=Vec3i(x=9, y=64, z=2),
            )
        )
    )
    assert "Symmetry (HARD): custom axis" in text
    assert "(1,64,2)" in text
    assert "(9,64,2)" in text


def test_custom_axis_without_axis_points_falls_back_to_anchor_symmetry():
    text = format_spatial_constraints(
        _req(symmetry=SymmetryConstraint(mode="CUSTOM_AXIS"))
    )
    assert "axis points are missing" in text


@pytest.mark.parametrize("mode", ["MIRROR_Y", "BOTH", "CUSTOM_AXIS"])
def test_modes_without_global_enum_never_instruct_a_bogus_enum(mode):
    """global_constraints.symmetry 只有 NONE/MIRROR_X/MIRROR_Z/RADIAL。

    其余模式必须走 alignment_and_symmetry 表达，提示词不得指示后端写非法枚举。
    """
    text = format_spatial_constraints(
        _req(
            symmetry=SymmetryConstraint(
                mode=mode,
                axisA=Vec3i(x=1, y=64, z=2),
                axisB=Vec3i(x=9, y=64, z=2),
            )
        )
    )
    assert "global_constraints.symmetry=MIRROR_Y" not in text
    assert "global_constraints.symmetry=BOTH" not in text
    assert "global_constraints.symmetry=CUSTOM_AXIS" not in text
    assert "alignment_and_symmetry" in text
