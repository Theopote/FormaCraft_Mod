from unittest.mock import Mock

import pytest

from app.models.request import BuildRequest
from app.routes import build


def request(format, mode, text):
    return BuildRequest(player={"name": "test", "pos": {"x": 0, "y": 64, "z": 0}, "facing": "SOUTH"},
                        world={"dimension": "minecraft:overworld"}, requestText=text,
                        outputFormat=format, promptMode=mode)


@pytest.mark.parametrize("format", [None, "auto", "llmplan"])
@pytest.mark.parametrize("mode", [None, "BUILD", "PATCH", "MODIFY_REGION"])
@pytest.mark.parametrize("text", ["city", "village", "house"])
def test_implicit_requests_use_canonical_pipeline(monkeypatch, format, mode, text):
    canonical = Mock(return_value={"components": []})
    monkeypatch.setattr(build, "generate_llm_plan", canonical)
    monkeypatch.setattr(build, "_maybe_clarify", lambda req: None)
    for name in ("generate_city_spec", "generate_composite_spec", "generate_building_spec"):
        monkeypatch.setattr(build, name, Mock(side_effect=AssertionError("legacy route selected")))
    req = request(format, mode, text)
    assert build._generate_response(req)["kind"] == "llmplan"
    canonical.assert_called_once_with(req)


@pytest.mark.parametrize("text,kind,function", [("city", "city", "generate_city_spec"),
    ("village", "composite", "generate_composite_spec"), ("house", "buildingspec", "generate_building_spec")])
def test_explicit_legacy_format_keeps_existing_consumers(monkeypatch, text, kind, function):
    generator = Mock(return_value={})
    monkeypatch.setattr(build, function, generator)
    req = request("buildingspec", None, text)
    assert build._generate_response(req)["kind"] == kind
    generator.assert_called_once_with(req)
