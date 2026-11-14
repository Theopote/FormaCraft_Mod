import pytest

from app.models.request import BuildRequest
from app.services.planning_request_hints import _infer_scale, _infer_terrain


def request(text, selection=None):
    return BuildRequest(
        player={"name": "tester", "pos": {"x": 0, "y": 64, "z": 0}, "facing": "SOUTH"},
        world={"dimension": "minecraft:overworld"},
        requestText=text,
        selection=selection,
    )


@pytest.mark.parametrize("span,expected", [(1, "SMALL"), (48, "SMALL"),
                                         (49, "MEDIUM"), (96, "MEDIUM"), (97, "LARGE")])
@pytest.mark.parametrize("axis", ["x", "z"])
@pytest.mark.parametrize("direction", [-1, 1])
def test_selection_size_overrides_text_with_inclusive_bounds(span, expected, axis, direction):
    end = {"x": 0, "y": 200, "z": 0}
    end[axis] = direction * (span - 1)
    selection = {"min": {"x": 0, "y": 0, "z": 0}, "max": end}
    assert _infer_scale(request("huge tiny", selection)) == expected


@pytest.mark.parametrize("text,expected", [
    ("", "MEDIUM"), ("a HUGE house", "LARGE"), ("tiny house", "SMALL"),
    ("large small", "LARGE"), ("大型建筑", "LARGE"), ("小型建筑", "SMALL"),
])
def test_text_scale_hints(text, expected):
    assert _infer_scale(request(text)) == expected


@pytest.mark.parametrize("text,expected", [
    ("", "UNKNOWN"), ("FLAT mountain hill", "FLAT"),
    ("mountain hill", "MOUNTAINOUS"), ("hilly", "HILLY"),
    ("平原", "FLAT"), ("山地", "MOUNTAINOUS"), ("丘陵", "HILLY"),
])
def test_terrain_keywords_keep_priority(text, expected):
    assert _infer_terrain(request(text)) == expected


def test_planner_compatibility_imports():
    from app.services import ai_planner

    assert ai_planner._infer_scale is _infer_scale
    assert ai_planner._infer_terrain is _infer_terrain
