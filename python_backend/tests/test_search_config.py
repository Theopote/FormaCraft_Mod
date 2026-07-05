"""Tests for per-request search configuration."""

from __future__ import annotations

import os
import unittest
from unittest.mock import MagicMock, patch

from app.services.search_config import (
    SearchRuntimeConfig,
    make_search_fn,
    resolve_search_config,
)


class SearchConfigTest(unittest.TestCase):
    def test_resolve_from_request_over_env(self):
        req = MagicMock()
        req.searchProvider = "bing"
        req.searchApiKey = "req-key"
        req.googleCseCx = "cx-123"
        with patch.dict(os.environ, {"BING_SEARCH_API_KEY": "env-key", "GOOGLE_CSE_CX": "env-cx"}, clear=False):
            cfg = resolve_search_config(req)
        self.assertEqual(cfg.provider, "bing")
        self.assertEqual(cfg.bing_api_key, "req-key")
        self.assertEqual(cfg.google_cse_cx, "cx-123")

    def test_resolve_falls_back_to_env(self):
        with patch.dict(
            os.environ,
            {
                "SEARCH_PROVIDER": "google_cse",
                "GOOGLE_CSE_API_KEY": "g-key",
                "GOOGLE_CSE_CX": "g-cx",
            },
            clear=False,
        ):
            cfg = resolve_search_config(None)
        self.assertEqual(cfg.provider, "google_cse")
        self.assertEqual(cfg.google_api_key, "g-key")
        self.assertEqual(cfg.google_cse_cx, "g-cx")

    def test_invalid_provider_defaults_to_auto(self):
        req = MagicMock()
        req.searchProvider = "unknown_engine"
        req.searchApiKey = None
        req.googleCseCx = None
        cfg = resolve_search_config(req)
        self.assertEqual(cfg.provider, "auto")

    @patch("app.services.architecture_researcher.search_architecture_reference")
    def test_make_search_fn_binds_config(self, mock_search):
        mock_search.return_value = [{"title": "t", "snippet": "s", "url": "u"}]
        req = MagicMock()
        req.searchProvider = "wikipedia_only"
        req.searchApiKey = None
        req.googleCseCx = None
        fn = make_search_fn(req)
        out = fn("埃菲尔铁塔", 2)
        self.assertEqual(len(out), 1)
        mock_search.assert_called_once()
        _args, kwargs = mock_search.call_args
        self.assertEqual(_args[0], "埃菲尔铁塔")
        self.assertIsInstance(kwargs.get("cfg"), SearchRuntimeConfig)
        self.assertEqual(kwargs["cfg"].provider, "wikipedia_only")


if __name__ == "__main__":
    unittest.main()
