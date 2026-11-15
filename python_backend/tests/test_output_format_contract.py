import pytest
from pydantic import ValidationError

from app.models.request import BuildRequest
from app.models.request_adapter import FormaRequestAdapter


def data():
    return {"player": {"name": "test", "pos": {"x": 0, "y": 64, "z": 0}, "facing": "SOUTH"},
            "world": {"dimension": "minecraft:overworld"}, "requestText": "house"}


def test_both_request_shapes_default_to_canonical_format():
    assert BuildRequest(**data()).outputFormat == "llmplan"
    assert FormaRequestAdapter(requestText="house").to_build_request().outputFormat == "llmplan"


@pytest.mark.parametrize("format", [None, "auto", "llmplan", "buildingspec"])
def test_existing_explicit_formats_remain_valid(format):
    assert BuildRequest(**data(), outputFormat=format).outputFormat == format
    assert FormaRequestAdapter(requestText="house", outputFormat=format).to_build_request().outputFormat == format


@pytest.mark.parametrize("format", ["building_spec", "llm_plan", "unknown", ""])
def test_unknown_formats_are_rejected_in_both_shapes(format):
    with pytest.raises(ValidationError):
        BuildRequest(**data(), outputFormat=format)
    with pytest.raises(ValidationError):
        FormaRequestAdapter(requestText="house", outputFormat=format)
