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
