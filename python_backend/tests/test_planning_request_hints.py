import pytest

from app.models.request import BuildRequest
from app.services.planning_request_hints import _infer_scale, _infer_terrain
from app.services.planning_request_hints import _parse_levels_from_text, _derive_structured_intent


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
    assert ai_planner._parse_levels_from_text is _parse_levels_from_text
    assert ai_planner._derive_structured_intent is _derive_structured_intent


@pytest.mark.parametrize("text,expected", [
    ("", None), ("ordinary house", None), ("7层", 7), ("层数为 8", 8),
    ("LEVELS: 9", 9), ("3 floors", 3), ("七层", 7), ("两层", 2),
    ("十二层", 12), ("二十层", 20), ("二十三层", 23), ("0层", None),
    ("层数 4, 7层", 4),
])
def test_existing_floor_count_expressions_and_priority(text, expected):
    assert _parse_levels_from_text(text) == expected


def test_soft_intent_preserves_keyword_priority_and_material_order():
    hint = _derive_structured_intent("a small modern wood stone glass house tower")
    assert "the user's own text takes priority" in hint
    assert "- purpose: residential house" in hint
    assert "- scale: small / compact" in hint
    assert "- style: Modern" in hint
    assert "- materials: wood, stone/brick, glass" in hint
    assert hint.endswith("and style-consistent materials.")


def test_soft_intent_empty_and_default_scale():
    assert _derive_structured_intent("  ") == ""
    assert "- scale: medium" in _derive_structured_intent("a building")
