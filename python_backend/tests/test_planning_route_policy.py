import pytest

from app.models.request import BuildRequest
from app.services.planning_route_policy import _should_generate_city, _should_generate_composite


def request(text, mode=None):
    return BuildRequest(
        player={"name": "tester", "pos": {"x": 0, "y": 64, "z": 0}, "facing": "SOUTH"},
        world={"dimension": "minecraft:overworld"},
        requestText=text,
        promptMode=mode,
    )


@pytest.mark.parametrize("text,city,composite", [
    ("建造城市和多个城堡", True, False),
    ("A TOWN with several houses", True, False),
    ("建造一个建筑群", False, True),
    ("a village compound", False, True),
    ("盖一个小房子", False, False),
    ("", False, False),
])
def test_existing_classification_and_city_priority(text, city, composite):
    req = request(text)
    assert _should_generate_city(req) is city
    assert _should_generate_composite(req) is composite


@pytest.mark.parametrize("marker", [
    "LlmPlan", "component_type", "semantic components", "ComponentObject",
    "SlotObject", "STRUCTURED JSON TEMPLATE",
])
def test_build_templates_keep_city_routing_disabled(marker):
    assert not _should_generate_city(request(f"{marker} city", "BUILD"))
    assert _should_generate_city(request(f"{marker} city", "PATCH"))


def test_existing_planner_imports_remain_available():
    from app.services import ai_planner

    assert ai_planner._should_generate_city is _should_generate_city
    assert ai_planner._should_generate_composite is _should_generate_composite
