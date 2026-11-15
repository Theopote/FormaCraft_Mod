from unittest.mock import Mock

import pytest

from app.models.request import BuildRequest
from app.services import ai_planner, building_research_agent as research


@pytest.mark.parametrize("explicit,legacy,enabled", [
    ("on", "off", True), ("off", "all", False),
    (None, "off", False), (None, "landmark", True),
])
def test_explicit_research_setting_wins_over_legacy(monkeypatch, explicit, legacy, enabled):
    monkeypatch.delenv("BUILDING_RESEARCH", raising=False)
    if explicit is not None:
        monkeypatch.setenv("BUILDING_RESEARCH", explicit)
    monkeypatch.setenv("ARCHITECTURE_SEARCH", legacy)
    assert research.is_building_research_enabled() is enabled


class ResearchStageComplete(BaseException):
    """Stop the integration probe before prompt enrichment and LLM execution."""


@pytest.mark.parametrize("explicit,expected_calls", [("on", 1), ("off", 0)])
def test_planner_uses_current_agent_setting_without_legacy_snapshot(monkeypatch, explicit, expected_calls):
    monkeypatch.setenv("ARCHITECTURE_SEARCH", "off")
    monkeypatch.setenv("BUILDING_RESEARCH", explicit)
    monkeypatch.setattr(ai_planner, "get_client", lambda req: object())
    monkeypatch.setattr(ai_planner, "_call_with_timeout", lambda call, timeout: call())
    profile_call = Mock(return_value=None)
    monkeypatch.setattr(research, "research_building_profile", profile_call)

    def stop_after_research(*args):
        raise ResearchStageComplete()

    monkeypatch.setattr(research, "ensure_building_profile_for_plan", stop_after_research)
    req = BuildRequest(player={"name": "test", "pos": {"x": 0, "y": 64, "z": 0}, "facing": "SOUTH"},
                       world={"dimension": "minecraft:overworld"}, requestText="Build a tower")
    with pytest.raises(ResearchStageComplete):
        ai_planner.generate_llm_plan(req)
    assert profile_call.call_count == expected_calls
