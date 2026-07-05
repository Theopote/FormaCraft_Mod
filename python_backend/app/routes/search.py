"""Search API validation endpoint for the in-game settings panel."""

from __future__ import annotations

import logging
from typing import Any, Dict, Optional

from fastapi import APIRouter, Header, Query

from ..services.search_config import SearchRuntimeConfig, resolve_search_config
from ..services.search_validate import validate_search_credentials

router = APIRouter()
logger = logging.getLogger("formacraft.search")


class _Tmp:
    """Minimal request-shaped object for resolve_search_config."""

    def __init__(
        self,
        *,
        search_provider: Optional[str],
        search_api_key: Optional[str],
        google_cse_cx: Optional[str],
    ):
        self.searchProvider = search_provider
        self.searchApiKey = search_api_key
        self.googleCseCx = google_cse_cx


@router.get("/search/validate")
def search_validate(
    provider: Optional[str] = Query(default=None),
    google_cse_cx: Optional[str] = Query(default=None),
    authorization: Optional[str] = Header(default=None),
) -> Dict[str, Any]:
    """
    轻量搜索 Key 校验：
    - 客户端 Settings 面板「测试搜索 Key」按钮调用
    - 对所选 provider 执行一次探测查询
    """
    api_key = None
    if authorization:
        v = authorization.strip()
        if v.lower().startswith("bearer "):
            api_key = v[7:].strip()

    req = _Tmp(
        search_provider=provider,
        search_api_key=api_key,
        google_cse_cx=google_cse_cx,
    )
    cfg = resolve_search_config(req)
    if provider and provider.strip():
        cfg = SearchRuntimeConfig(
            provider=provider.strip().lower(),
            bing_api_key=cfg.bing_api_key,
            google_api_key=cfg.google_api_key,
            google_cse_cx=(google_cse_cx or cfg.google_cse_cx or "").strip(),
            tavily_api_key=cfg.tavily_api_key,
            serpapi_api_key=cfg.serpapi_api_key,
        )

    logger.info(
        "GET /search/validate provider=%s has_auth=%s has_cx=%s",
        cfg.provider,
        bool(api_key),
        bool(cfg.google_cse_cx),
    )
    return validate_search_credentials(cfg)
