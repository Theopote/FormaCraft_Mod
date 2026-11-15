import json

import pytest

from app.services import llm_plan_wire_normalization as wire


@pytest.mark.parametrize("value,expected", [(None, ""), (" main_mass ", "MASS_MAIN"),
                                         ("buttress", "WALL"), ("new_type", "NEW_TYPE")])
def test_type_aliases_preserve_unknown_values(value, expected):
    assert wire._normalize_component_type(value) == expected


def test_feature_objects_and_requests_preserve_payloads():
    component = {"query": "中式窗", "filters": {"size": [2, 3]}}
    group = {"name": "doors"}
    result = wire._normalize_features([None, " ", "roof", {"component_request": component},
                                       {"group_request": group}, {"custom": True}, 3])
    assert result[0] == "roof"
    assert json.loads(result[1].removeprefix("component_request:")) == component
    assert json.loads(result[2].removeprefix("group_request:")) == group
    assert json.loads(result[3]) == {"custom": True}
    assert result[4] == "3"


@pytest.mark.parametrize("value,expected", [(None, []), (42, []), ("", [""]), ("roof", ["roof"])])
def test_feature_scalar_contract(value, expected):
    assert wire._normalize_features(value) == expected


@pytest.mark.parametrize("prefix", ["component_request", "group_request"])
def test_repairs_unescaped_request_json_with_nested_braces_and_quotes(prefix):
    payload = {"query": {"text": 'a } brace and "quote"', "nested": {"value": 2}}}
    raw = '{"features":["' + prefix + ':' + json.dumps(payload) + '","roof"]}'
    repaired = wire._repair_component_request_strings(raw)
    assert json.loads(repaired) == {"features": [{prefix: payload}, "roof"]}


def test_valid_text_without_requests_is_unchanged():
    raw = '{"features":["roof"]}'
    assert wire._repair_component_request_strings(raw) == raw
    assert wire._repair_component_request_strings("") == ""


def test_planner_import_aliases():
    from app.services import ai_planner

    for name in ("_normalize_component_type", "_normalize_features", "_repair_component_request_strings"):
        assert getattr(ai_planner, name) is getattr(wire, name)
