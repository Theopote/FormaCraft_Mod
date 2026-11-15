from types import SimpleNamespace
from unittest.mock import Mock

import pytest

from app.models.building_spec import BuildingSpec
from app.models.city_spec import CitySpec
from app.services import ai_editor


@pytest.mark.parametrize("model,provider,expected", [
    ("custom-model", "deepseek", "custom-model"),
    (None, "deepseek", "deepseek-chat"),
    (None, "openai", "gpt-4o-mini"),
])
def test_model_resolution_uses_shared_provider_policy(monkeypatch, model, provider, expected):
    for key in ("DEEPSEEK_MODEL", "OPENAI_MODEL", "LLM_PROVIDER", "LLM_BASE_URL"):
        monkeypatch.delenv(key, raising=False)
    assert ai_editor._resolve_model_from_fields(model, provider, None, "gpt-4o-mini") == expected


@pytest.fixture(params=["building", "city"])
def edit_case(request):
    if request.param == "building":
        current = BuildingSpec(type="HOUSE", footprint={"shape": "rectangle"}, materials={}, features={})
        updated = current.model_copy(update={"height": 25})
        return ai_editor.edit_building_spec, current, updated
    current = CitySpec(cityName="Original", style="MEDIEVAL", size="SMALL", biome="plains",
                       zones=[], structures=[], roads=[], bridges=[])
    return ai_editor.edit_city_spec, current, current.model_copy(update={"cityName": "Updated"})


def test_edit_reaches_client_and_validates_response(monkeypatch, edit_case):
    edit, current, updated = edit_case
    create = Mock(return_value=SimpleNamespace(choices=[SimpleNamespace(
        message=SimpleNamespace(content=updated.model_dump_json()))]))
    factory = Mock(return_value=SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=create))))
    monkeypatch.setattr(ai_editor, "get_client_from_fields", factory)
    result = edit(current, "change it", api_key="key", model="custom", llm_provider="deepseek",
                  llm_base_url="https://example.invalid", temperature=2)
    factory.assert_called_once_with("key", "deepseek", "https://example.invalid")
    assert create.call_args.kwargs["model"] == "custom"
    assert create.call_args.kwargs["temperature"] == 1.0
    assert result.model_dump() == updated.model_dump()
    assert result is not current


@pytest.mark.parametrize("response", [None, "not-json", "{}"])
def test_invalid_response_preserves_current(monkeypatch, edit_case, response):
    edit, current, _ = edit_case
    create = Mock(return_value=SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=response))]))
    monkeypatch.setattr(ai_editor, "get_client_from_fields", lambda *args: SimpleNamespace(
        chat=SimpleNamespace(completions=SimpleNamespace(create=create))))
    assert edit(current, "change it", model="custom") is current


def test_no_client_preserves_current(monkeypatch, edit_case):
    edit, current, _ = edit_case
    monkeypatch.setattr(ai_editor, "get_client_from_fields", lambda *args: None)
    assert edit(current, "change it") is current
