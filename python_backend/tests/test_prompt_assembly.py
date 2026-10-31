"""Prompt ownership: no silent truncation on USER REQUEST marker in user text."""

from app.models.request import BuildRequest, PlayerInfo, WorldContext, Vec3i
from app.services.prompt_assembly import resolve_llm_plan_prompts
from app.services.llm_plan_system_prompt import build_llm_plan_system_prompt


def _req(**kwargs) -> BuildRequest:
    base = dict(
        player=PlayerInfo(name="p", pos=Vec3i(x=0, y=64, z=0), facing="NORTH"),
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
    assert user == marker_text
    assert "Formacraft Core" in system


def test_explicit_fields_win():
    system, user = resolve_llm_plan_prompts(
        _req(systemPrompt="SYS", userPrompt="USR", userMessage="ignored"),
        default_system="DEFAULT",
    )
    assert system == "SYS"
    assert user == "USR"
