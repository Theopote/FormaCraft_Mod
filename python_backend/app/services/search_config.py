"""
Resolve web search provider credentials from BuildRequest or environment.

Priority: request fields (from game settings) > env vars > defaults.
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Any, Callable, Dict, List

VALID_SEARCH_PROVIDERS = frozenset({
    "auto",
    "duckduckgo",
    "bing",
    "google_cse",
    "tavily",
    "serpapi",
    "wikipedia_only",
})


@dataclass(frozen=True)
class SearchRuntimeConfig:
    provider: str = "auto"
    bing_api_key: str = ""
    google_api_key: str = ""
    google_cse_cx: str = ""
    tavily_api_key: str = ""
    serpapi_api_key: str = ""


def _pick(req: Any, attr: str, env_name: str, default: str = "") -> str:
    if req is not None:
        v = getattr(req, attr, None)
        if v is not None and str(v).strip():
            return str(v).strip()
    return (os.getenv(env_name) or default).strip()


def resolve_search_config(req: Any = None) -> SearchRuntimeConfig:
    provider = _pick(req, "searchProvider", "SEARCH_PROVIDER", "auto").lower()
    if provider not in VALID_SEARCH_PROVIDERS:
        provider = "auto"

    # Per-provider env keys (always). Never broadcast one shared key across vendors.
    bing_key = (os.getenv("BING_SEARCH_API_KEY") or "").strip()
    google_key = (os.getenv("GOOGLE_CSE_API_KEY") or "").strip()
    tavily_key = (os.getenv("TAVILY_API_KEY") or "").strip()
    serpapi_key = (os.getenv("SERPAPI_API_KEY") or "").strip()
    google_cx = _pick(req, "googleCseCx", "GOOGLE_CSE_CX")

    # Client/env searchApiKey binds ONLY when an explicit paid provider is selected.
    # auto / duckduckgo / wikipedia_only must not fan the same secret into every vendor.
    shared_key = _pick(req, "searchApiKey", "SEARCH_API_KEY")
    if shared_key:
        if provider == "bing":
            bing_key = shared_key
        elif provider == "google_cse":
            google_key = shared_key
        elif provider == "tavily":
            tavily_key = shared_key
        elif provider == "serpapi":
            serpapi_key = shared_key

    return SearchRuntimeConfig(
        provider=provider,
        bing_api_key=bing_key,
        google_api_key=google_key,
        google_cse_cx=google_cx,
        tavily_api_key=tavily_key,
        serpapi_api_key=serpapi_key,
    )


def make_search_fn(req: Any = None) -> Callable[[str, int], List[Dict[str, str]]]:
    """Build a search callable bound to request/env search settings."""
    cfg = resolve_search_config(req)

    def _search(query: str, max_results: int = 3) -> List[Dict[str, str]]:
        from .architecture_researcher import search_architecture_reference

        return search_architecture_reference(query, max_results=max_results, cfg=cfg)

    return _search
