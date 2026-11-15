import sys
from unittest.mock import Mock

import pytest

from app.services import architecture_researcher as search


@pytest.mark.parametrize("query,expected_query,region", [
    ("tower", "tower architecture structure design", "wt-wt"),
    ("museum architecture", "museum architecture", "wt-wt"),
    ("建筑设计", "建筑设计", "cn-zh"),
])
def test_ddgs_backend_query_and_result_mapping(monkeypatch, query, expected_query, region):
    import ddgs

    text = Mock(return_value=[{"title": "Museum", "body": "Facade", "href": "https://example.invalid"}, {}])
    monkeypatch.setattr(ddgs, "DDGS", Mock(return_value=Mock(text=text)))
    result = search._search_with_duckduckgo(query, 2)
    text.assert_called_once_with(expected_query, max_results=2, region=region, backend="duckduckgo")
    assert result == [{"title": "Museum", "snippet": "Facade", "url": "https://example.invalid"},
                      {"title": "", "snippet": "", "url": ""}]


def test_missing_ddgs_uses_http_fallback(monkeypatch):
    monkeypatch.setitem(sys.modules, "ddgs", None)
    fallback = Mock(return_value=[{"title": "Fallback"}])
    monkeypatch.setattr(search, "_search_with_http", fallback)
    assert search._search_with_duckduckgo("tower", 3) == [{"title": "Fallback"}]
    fallback.assert_called_once_with("tower", 3)


def test_provider_failure_remains_visible_to_search_chain(monkeypatch):
    import ddgs

    monkeypatch.setattr(ddgs, "DDGS", Mock(side_effect=RuntimeError("provider failed")))
    with pytest.raises(RuntimeError, match="provider failed"):
        search._search_with_duckduckgo("tower", 3)
