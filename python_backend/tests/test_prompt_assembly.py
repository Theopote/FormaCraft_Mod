"""Prompt ownership: backend owns system prompt; spatial constraints injected."""

from app.models.request import (
    BuildRequest,
    OutlineShape,
    PathConstraint,
    PlayerInfo,
    ProtectedZone,
    Selection,
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
    assert "ASSEMBLY COMPONENT LIBRARY" in system
    assert "LlmPlan JSON shape" in system
    assert "ASSEMBLY COMPONENT LIBRARY" in system
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
