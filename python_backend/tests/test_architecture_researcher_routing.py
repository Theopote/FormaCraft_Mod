"""Provider routing / secret redaction for architecture_researcher."""

from __future__ import annotations

import unittest
from unittest.mock import MagicMock, patch

import requests

from app.services.architecture_researcher import (
    redact_secrets,
    safe_error_message,
    search_architecture_reference,
)
from app.services.search_config import SearchRuntimeConfig


class SecretRedactionTest(unittest.TestCase):
    def test_redact_api_key_query_params(self):
        raw = "401 for url: https://serpapi.com/search.json?api_key=SECRET123&q=tower"
        self.assertNotIn("SECRET123", redact_secrets(raw))
        self.assertIn("api_key=***", redact_secrets(raw))

    def test_safe_error_message_for_http_error(self):
        resp = MagicMock()
        resp.status_code = 403
        err = requests.HTTPError("403 Client Error for url: https://x?key=LEAK")
        err.response = resp
        self.assertEqual(safe_error_message(err), "HTTP 403")


class ExplicitProviderRoutingTest(unittest.TestCase):
    @patch("app.services.architecture_researcher._search_wikipedia")
    @patch("app.services.architecture_researcher._search_with_tavily")
    def test_explicit_tavily_runs_before_wikipedia(self, mock_tavily, mock_wiki):
        mock_tavily.return_value = [
            {
                "title": "Paid hit architecture",
                "snippet": "structure design",
                "url": "https://paid.example/tavily",
            }
        ]
        mock_wiki.return_value = [
            {
                "title": "Wiki hit",
                "snippet": "architecture",
                "url": "https://en.wikipedia.org/wiki/X",
            }
        ]
        cfg = SearchRuntimeConfig(provider="tavily", tavily_api_key="tvly-test")
        out = search_architecture_reference("Eiffel Tower", max_results=1, cfg=cfg)
        mock_tavily.assert_called_once()
        mock_wiki.assert_not_called()
        self.assertEqual(out[0]["url"], "https://paid.example/tavily")

    @patch("app.services.architecture_researcher._search_wikipedia")
    @patch("app.services.architecture_researcher._search_with_duckduckgo")
    def test_explicit_duckduckgo_tops_up_with_wikipedia(self, mock_ddg, mock_wiki):
        mock_ddg.return_value = []
        mock_wiki.return_value = [
            {
                "title": "Wiki architecture",
                "snippet": "building",
                "url": "https://en.wikipedia.org/wiki/Y",
            }
        ]
        cfg = SearchRuntimeConfig(provider="duckduckgo")
        out = search_architecture_reference("Sydney Opera House", max_results=1, cfg=cfg)
        mock_ddg.assert_called_once()
        self.assertTrue(mock_wiki.called)
        self.assertEqual(out[0]["url"], "https://en.wikipedia.org/wiki/Y")


if __name__ == "__main__":
    unittest.main()
