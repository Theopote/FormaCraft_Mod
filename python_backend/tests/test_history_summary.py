from types import SimpleNamespace
from unittest.mock import Mock

import pytest
from fastapi import HTTPException

from app.routes.history import SummarizeRequest, summarize_endpoint
from app.services import llm_client


def summarize(**fields):
    # summarize_endpoint is sync so it runs on FastAPI's threadpool, not the event loop.
    return summarize_endpoint(SummarizeRequest(**fields))


def test_empty_transcript_does_not_create_client(monkeypatch):
    factory = Mock(side_effect=AssertionError("unexpected client creation"))
    monkeypatch.setattr(llm_client, "get_client", factory)
    assert summarize(transcript="  ") == {"title": "新对话", "summary": ""}
    factory.assert_not_called()


def test_no_client_preserves_truncated_local_summary(monkeypatch):
    monkeypatch.setattr(llm_client, "get_client", lambda req: None)
    text = "a" * 200
    assert summarize(transcript=text) == {"title": "a" * 27 + "…", "summary": "a" * 179 + "…"}


def test_provider_fields_reach_shared_client_and_model_resolution(monkeypatch):
    create = Mock(return_value=SimpleNamespace(choices=[SimpleNamespace(
        message=SimpleNamespace(content='{"title":"Example","summary":"Built a tower"}'))]))
    client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=create)))
    factory = Mock(return_value=client)
    config = Mock(return_value=SimpleNamespace(model="provider-model"))
    monkeypatch.setattr(llm_client, "get_client", factory)
    monkeypatch.setattr(llm_client, "build_config", config)
    result = summarize(transcript="tower", llmProvider="deepseek", llmBaseUrl="https://example.invalid",
                       apiKey="test-key", model="custom", temperature=2)
    req = factory.call_args.args[0]
    assert (req.llmProvider, req.llmBaseUrl, req.apiKey, req.model) == (
        "deepseek", "https://example.invalid", "test-key", "custom")
    config.assert_called_once_with(req, default_model="gpt-4o-mini")
    assert create.call_args.kwargs["model"] == "provider-model"
    assert create.call_args.kwargs["temperature"] == 1.0
    assert result == {"title": "Example", "summary": "Built a tower"}


def test_client_failure_preserves_http_error(monkeypatch):
    client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(
        create=Mock(side_effect=RuntimeError("failed")))))
    monkeypatch.setattr(llm_client, "get_client", lambda req: client)
    monkeypatch.setattr(llm_client, "build_config", lambda *args, **kwargs: SimpleNamespace(model="test"))
    with pytest.raises(HTTPException) as error:
        summarize(transcript="tower")
    assert error.value.status_code == 500
    assert error.value.detail == "Failed to summarize: failed"
