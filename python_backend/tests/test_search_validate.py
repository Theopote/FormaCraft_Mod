"""Tests for search validation and Tavily/SerpAPI config."""

from __future__ import annotations

import os
import unittest
from unittest.mock import MagicMock, patch

from app.services.search_config import SearchRuntimeConfig, resolve_search_config
from app.services.search_validate import validate_search_credentials


class SearchValidateTest(unittest.TestCase):
    def test_tavily_requires_key(self):
        cfg = SearchRuntimeConfig(provider="tavily", tavily_api_key="")
        out = validate_search_credentials(cfg)
        self.assertFalse(out["ok"])
        self.assertIn("Tavily", out["message_zh"])

    def test_serpapi_requires_key(self):
        cfg = SearchRuntimeConfig(provider="serpapi", serpapi_api_key="")
        out = validate_search_credentials(cfg)
        self.assertFalse(out["ok"])
        self.assertIn("SerpAPI", out["message_zh"])

    @patch("app.services.architecture_researcher._search_with_tavily")
    def test_tavily_validate_ok(self, mock_tavily):
        mock_tavily.return_value = [{"title": "Eiffel", "snippet": "tower", "url": "https://x"}]
        cfg = SearchRuntimeConfig(provider="tavily", tavily_api_key="tvly-test")
        out = validate_search_credentials(cfg)
        self.assertTrue(out["ok"])
        self.assertEqual(out["provider"], "tavily")
        mock_tavily.assert_called_once()

    @patch("app.services.architecture_researcher._search_with_serpapi")
    def test_serpapi_validate_ok(self, mock_serp):
        mock_serp.return_value = [{"title": "Eiffel", "snippet": "tower", "url": "https://x"}]
        cfg = SearchRuntimeConfig(provider="serpapi", serpapi_api_key="serp-test")
        out = validate_search_credentials(cfg)
        self.assertTrue(out["ok"])
        mock_serp.assert_called_once()

    def test_resolve_tavily_from_env(self):
        with patch.dict(os.environ, {"TAVILY_API_KEY": "env-tvly", "SEARCH_PROVIDER": "tavily"}, clear=False):
            cfg = resolve_search_config(None)
        self.assertEqual(cfg.provider, "tavily")
        self.assertEqual(cfg.tavily_api_key, "env-tvly")


class TavilySerpapiSearchTest(unittest.TestCase):
    @patch("app.services.architecture_researcher.requests.post")
    def test_tavily_search_parses_results(self, mock_post):
        from app.services.architecture_researcher import _search_with_tavily

        mock_post.return_value.status_code = 200
        mock_post.return_value.json.return_value = {
            "results": [
                {"title": "A", "content": "body", "url": "https://a"},
            ]
        }
        mock_post.return_value.raise_for_status = MagicMock()
        out = _search_with_tavily("test", 2, cfg=SearchRuntimeConfig(tavily_api_key="k"))
        self.assertEqual(len(out), 1)
        self.assertEqual(out[0]["title"], "A")

    @patch("app.services.architecture_researcher.requests.get")
    def test_serpapi_search_parses_results(self, mock_get):
        from app.services.architecture_researcher import _search_with_serpapi

        mock_get.return_value.status_code = 200
        mock_get.return_value.json.return_value = {
            "organic_results": [
                {"title": "B", "snippet": "snip", "link": "https://b"},
            ]
        }
        mock_get.return_value.raise_for_status = MagicMock()
        out = _search_with_serpapi("test", 2, cfg=SearchRuntimeConfig(serpapi_api_key="k"))
        self.assertEqual(len(out), 1)
        self.assertEqual(out[0]["url"], "https://b")


if __name__ == "__main__":
    unittest.main()
