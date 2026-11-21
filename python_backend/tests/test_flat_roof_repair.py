import json
from types import SimpleNamespace
from unittest.mock import Mock, patch

import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.services.flat_roof_repair import validate_flat_roof_proposal


@pytest.fixture
def payload():
    return {
        "request": {"userMessage": "建造云纹折带馆，不要使用预设。", "apiKey": "secret-test-value"},
        "round": 1,
        "plan": {"components": [
            {"component_type": "ROOF", "dimensions": {"width": 7, "depth": 9, "height": 1},
             "params": {"component_id": "roof-a", "host_id": "hall", "roof_type": "flat"}}
        ]},
        "diagnostic": {"code": "E_FLAT_ROOF_HOST_COVERAGE", "details": {
            "host_id": "hall", "host_part_id": "hall", "roof_sources": ["roof-a"],
        }},
    }


def test_endpoint_returns_one_proposal_without_mutating_plan(payload):
    proposal = {"component_id": "roof-a", "field": "dimensions.width", "value": 9}
    client = Mock()
    completion = client.with_options.return_value.chat.completions.create
    completion.return_value = SimpleNamespace(choices=[
        SimpleNamespace(message=SimpleNamespace(content=json.dumps(proposal)))
    ])
    with patch("app.services.ai_planner.get_client", return_value=client), patch(
        "app.services.ai_planner._call_with_timeout", side_effect=lambda fn, timeout: fn()
    ):
        response = TestClient(app).post("/components/flat-roof/repair", json=payload)
    assert response.status_code == 200, response.text
    assert response.json() == proposal
    assert payload["plan"]["components"][0]["dimensions"]["width"] == 7
    completion.assert_called_once()
    assert "secret-test-value" not in str(completion.call_args.kwargs["messages"])


@pytest.mark.parametrize("overrides", [
    {"component_id": "foreign"}, {"field": "params.roof_type"}, {"value": 0},
    {"value": True}, {"value": 7}, {"value": "9"},
])
def test_invalid_edits_are_rejected(payload, overrides):
    proposal = {"component_id": "roof-a", "field": "dimensions.width", "value": 9}
    with pytest.raises(ValueError):
        validate_flat_roof_proposal(payload["plan"], payload["diagnostic"], proposal | overrides)


def test_explicit_dimensions_and_other_host_parts_are_protected(payload):
    proposal = {"component_id": "roof-a", "field": "dimensions.width", "value": 9}
    params = payload["plan"]["components"][0]["params"]
    for extra in ({"design_sources": {"dimensions": "USER_EXPLICIT"}},
                  {"host_part_id": "hall#other"}):
        with pytest.raises(ValueError):
            validate_flat_roof_proposal({"components": [
                payload["plan"]["components"][0] | {"params": params | extra}
            ]}, payload["diagnostic"], proposal)
