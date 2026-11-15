import pytest

from app.models.request import BuildRequest
from app.services import ai_planner, legacy_spec_fallbacks as fallbacks


def request(text):
    return BuildRequest(player={"name": "test", "pos": {"x": 0, "y": 64, "z": 0},
                                "facing": "SOUTH"},
                        world={"dimension": "minecraft:overworld"}, requestText=text)


@pytest.mark.parametrize("text,type,height", [("tower", "TOWER", 20),
                                           ("bridge", "BRIDGE", 1), ("house", "HOUSE", 4)])
def test_single_building_offline_defaults(text, type, height):
    result = fallbacks._generate_fallback_spec(request(text))
    assert result.type.value == type
    assert result.height == height
    assert "genome" in result.extra
    assert "component_params" in result.extra


@pytest.mark.parametrize("generate,fallback,text", [
    ("generate_building_spec", "_generate_fallback_spec", "house"),
    ("generate_city_spec", "_generate_fallback_city_spec", "city"),
    ("generate_composite_spec", "_generate_fallback_composite_spec", "village"),
])
def test_no_client_compatibility_route_matches_offline_fallback(monkeypatch, generate, fallback, text):
    monkeypatch.setattr(ai_planner, "get_client", lambda *args, **kwargs: None)
    req = request(text)
    expected = getattr(fallbacks, fallback)(req)
    actual = getattr(ai_planner, generate)(req)
    assert actual.model_dump() == expected.model_dump()


def test_existing_import_aliases():
    for name in ("_generate_fallback_spec", "_generate_fallback_city_spec", "_generate_fallback_composite_spec"):
        assert getattr(ai_planner, name) is getattr(fallbacks, name)
