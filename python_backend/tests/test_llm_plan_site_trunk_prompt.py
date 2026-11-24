"""Site trunk prompt + default routing stay on LlmPlan (no silent CitySpec)."""

from unittest.mock import Mock

from app.models.request import BuildRequest
from app.routes import build
from app.services.llm_plan_system_prompt import build_llm_plan_system_prompt
from app.services.planning_route_policy import _should_generate_city, _should_generate_composite


def _req(text, *, output_format=None, mode="BUILD"):
    return BuildRequest(
        player={"name": "tester", "pos": {"x": 0, "y": 64, "z": 0}, "facing": "SOUTH"},
        world={"dimension": "minecraft:overworld"},
        requestText=text,
        promptMode=mode,
        outputFormat=output_format,
    )


def test_system_prompt_teaches_multi_building_site_without_cityspec():
    prompt = build_llm_plan_system_prompt(_req("两座亭子和一条路"))
    assert "building_id" in prompt
    assert "params.from" in prompt
    assert "CitySpec" in prompt
    assert "NON-BINDING site composition" in prompt
    assert "不要城市街区" in prompt or "two pavilions" in prompt


def test_default_route_keeps_city_keyword_on_llmplan(monkeypatch):
    canonical = Mock(return_value={"components": [{"component_type": "MASS_MAIN"}]})
    monkeypatch.setattr(build, "generate_llm_plan", canonical)
    monkeypatch.setattr(build, "_maybe_clarify", lambda req: None)
    for name in ("generate_city_spec", "generate_composite_spec", "generate_building_spec"):
        monkeypatch.setattr(build, name, Mock(side_effect=AssertionError("legacy route selected")))

    for text in ("建造一座城市街区", "campus with several halls", "不要城市街区，只要两座亭子和一条路"):
        resp = build._generate_response(_req(text))
        assert resp["kind"] == "llmplan", text


def test_build_mode_llm_indicators_disable_city_compat_classifier():
    # Compat city keywords still exist for buildingspec, but BUILD+LlmPlan markers disable them.
    req = _req("LlmPlan component_type 城市", mode="BUILD")
    assert _should_generate_city(req) is False
    assert _should_generate_composite(req) is False
