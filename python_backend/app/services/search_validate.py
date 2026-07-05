"""
Validate search API credentials (used by GET /search/validate).
"""

from __future__ import annotations

import logging
from typing import Any, Dict, List, Optional

from .search_config import SearchRuntimeConfig, VALID_SEARCH_PROVIDERS

logger = logging.getLogger(__name__)

_TEST_QUERY = "Eiffel Tower architecture structure"


def _ok(
    provider: str,
    *,
    result_count: int = 0,
    message_zh: Optional[str] = None,
    detail: Optional[str] = None,
) -> Dict[str, Any]:
    msg = message_zh or f"搜索 Key 校验通过（{provider}），返回 {result_count} 条结果。"
    out: Dict[str, Any] = {
        "ok": True,
        "provider": provider,
        "message_zh": msg,
        "result_count": result_count,
    }
    if detail:
        out["detail"] = detail
    return out


def _fail(provider: str, message_zh: str, *, detail: Optional[str] = None) -> Dict[str, Any]:
    out: Dict[str, Any] = {
        "ok": False,
        "provider": provider,
        "message_zh": message_zh,
        "result_count": 0,
    }
    if detail:
        out["detail"] = detail
    return out


def _count_results(batch: List[Dict[str, str]]) -> int:
    return len(batch or [])


def validate_search_credentials(cfg: SearchRuntimeConfig) -> Dict[str, Any]:
    """Run a lightweight probe query against the selected search provider."""
    from .architecture_researcher import (
        _search_wikipedia,
        _search_with_bing,
        _search_with_duckduckgo,
        _search_with_google_cse,
        _search_with_serpapi,
        _search_with_tavily,
        google_cse_configured,
        serpapi_configured,
        tavily_configured,
    )

    provider = (cfg.provider or "auto").strip().lower()
    if provider not in VALID_SEARCH_PROVIDERS:
        provider = "auto"

    try:
        if provider == "wikipedia_only":
            batch = _search_wikipedia(_TEST_QUERY, max_results=1, lang="en")
            n = _count_results(batch)
            if n > 0:
                return _ok("wikipedia_only", result_count=n)
            return _fail("wikipedia_only", "Wikipedia 探测未返回结果，请检查网络连接。")

        if provider == "duckduckgo":
            batch = _search_with_duckduckgo(_TEST_QUERY, max_results=2)
            n = _count_results(batch)
            if n > 0:
                return _ok("duckduckgo", result_count=n, message_zh=f"DuckDuckGo 可用，返回 {n} 条结果。")
            return _fail("duckduckgo", "DuckDuckGo 未返回结果，可能被限流或网络不可达。")

        if provider == "google_cse":
            if not google_cse_configured(cfg):
                return _fail("google_cse", "请填写 Search API Key 与 Google CSE CX。")
            batch = _search_with_google_cse(_TEST_QUERY, max_results=2, cfg=cfg)
            n = _count_results(batch)
            if n > 0:
                return _ok("google_cse", result_count=n)
            return _fail("google_cse", "Google 自定义搜索未返回结果，请检查 API Key / CX 或配额。")

        if provider == "bing":
            if not (cfg.bing_api_key or "").strip():
                return _fail("bing", "请填写 Bing Search API Key。")
            batch = _search_with_bing(_TEST_QUERY, max_results=2, cfg=cfg)
            n = _count_results(batch)
            if n > 0:
                return _ok("bing", result_count=n)
            return _fail("bing", "Bing Search 未返回结果，请检查 API Key 或订阅状态。")

        if provider == "tavily":
            if not tavily_configured(cfg):
                return _fail("tavily", "请填写 Tavily API Key。")
            batch = _search_with_tavily(_TEST_QUERY, max_results=2, cfg=cfg)
            n = _count_results(batch)
            if n > 0:
                return _ok("tavily", result_count=n)
            return _fail("tavily", "Tavily 未返回结果，请检查 API Key 或账户配额。")

        if provider == "serpapi":
            if not serpapi_configured(cfg):
                return _fail("serpapi", "请填写 SerpAPI Key。")
            batch = _search_with_serpapi(_TEST_QUERY, max_results=2, cfg=cfg)
            n = _count_results(batch)
            if n > 0:
                return _ok("serpapi", result_count=n)
            return _fail("serpapi", "SerpAPI 未返回结果，请检查 API Key 或配额。")

        # auto: probe first configured paid API, else DuckDuckGo
        if google_cse_configured(cfg):
            batch = _search_with_google_cse(_TEST_QUERY, max_results=1, cfg=cfg)
            n = _count_results(batch)
            if n > 0:
                return _ok("auto", result_count=n, message_zh=f"自动模式：Google CSE 可用（{n} 条）。")
        if tavily_configured(cfg):
            batch = _search_with_tavily(_TEST_QUERY, max_results=1, cfg=cfg)
            n = _count_results(batch)
            if n > 0:
                return _ok("auto", result_count=n, message_zh=f"自动模式：Tavily 可用（{n} 条）。")
        if serpapi_configured(cfg):
            batch = _search_with_serpapi(_TEST_QUERY, max_results=1, cfg=cfg)
            n = _count_results(batch)
            if n > 0:
                return _ok("auto", result_count=n, message_zh=f"自动模式：SerpAPI 可用（{n} 条）。")
        if (cfg.bing_api_key or "").strip():
            batch = _search_with_bing(_TEST_QUERY, max_results=1, cfg=cfg)
            n = _count_results(batch)
            if n > 0:
                return _ok("auto", result_count=n, message_zh=f"自动模式：Bing 可用（{n} 条）。")

        batch = _search_with_duckduckgo(_TEST_QUERY, max_results=1)
        n = _count_results(batch)
        if n > 0:
            return _ok(
                "auto",
                result_count=n,
                message_zh="自动模式：未配置付费 API，DuckDuckGo 可用。",
            )
        wiki = _search_wikipedia(_TEST_QUERY, max_results=1, lang="en")
        if _count_results(wiki) > 0:
            return _ok(
                "auto",
                result_count=1,
                message_zh="自动模式：仅 Wikipedia 可达（建议配置 Tavily/SerpAPI/Bing/Google）。",
            )
        return _fail("auto", "自动模式探测失败：各搜索源均不可用，请检查网络或 API 配置。")

    except ValueError as e:
        return _fail(provider, str(e))
    except Exception as e:
        logger.warning("Search validate failed provider=%s: %s", provider, e)
        return _fail(provider, f"搜索校验异常：{e}", detail=type(e).__name__)
