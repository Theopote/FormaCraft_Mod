from fastapi import APIRouter, Header, Query
import json
import os
import urllib.request
import logging
import concurrent.futures
from typing import Any, Dict, List, Optional

from ..services.llm_client import (
    HAS_OPENAI,
    build_config,
    get_client_from_fields,
    pick_preferred_model,
    resolve_base_url,
    resolve_provider,
)

router = APIRouter()
logger = logging.getLogger("formacraft.models")

_REMOTE_MODELS_HARD_TIMEOUT_SEC = 2.5


_FALLBACK_MODELS_BY_PROVIDER: Dict[str, List[str]] = {
    # OpenAI family (examples; will vary by account availability)
    "openai": ["gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "gpt-4.1", "gpt-4"],
    "openai_compat": ["gpt-4o-mini", "gpt-4o"],
    "auto": ["gpt-4o-mini"],
    # DeepSeek
    "deepseek": ["deepseek-chat", "deepseek-reasoner"],
    # Groq (common public model ids)
    "groq": ["llama-3.1-70b-versatile", "llama-3.1-8b-instant", "mixtral-8x7b-32768", "gemma2-9b-it"],
    # Together (common examples; naming may change)
    "together": ["meta-llama/Llama-3.1-70B-Instruct-Turbo", "meta-llama/Llama-3.1-8B-Instruct-Turbo", "Qwen/Qwen2.5-72B-Instruct-Turbo"],
    # OpenRouter (very large catalog; provide a few popular examples)
    "openrouter": ["openai/gpt-4o-mini", "openai/gpt-4o", "anthropic/claude-3.5-sonnet", "google/gemini-1.5-pro"],
    # Local providers are user-dependent, but suggestions help first-time setup
    "ollama": ["llama3.1", "qwen2.5", "deepseek-r1"],
    "lmstudio": ["local-model"],
}


def _fetch_remote_models(base_url: str, api_key: str) -> List[str]:
    """
    Fetch models list from OpenAI-compatible endpoint: GET {base_url}/models
    Note: This may block on DNS/SSL in some environments. Callers should wrap it with a hard timeout.
    """
    url = base_url.rstrip("/") + "/models"
    req = urllib.request.Request(url, method="GET")
    req.add_header("Accept", "application/json")
    req.add_header("Authorization", f"Bearer {api_key}")
    with urllib.request.urlopen(req, timeout=2) as resp:
        body = resp.read().decode("utf-8", errors="ignore")
        data = json.loads(body)
        models_list: List[str] = []
        if isinstance(data, dict) and isinstance(data.get("data"), list):
            for item in data["data"]:
                if isinstance(item, dict) and isinstance(item.get("id"), str):
                    models_list.append(item["id"])
        return models_list


@router.get("/models")
def models(
    provider: Optional[str] = Query(default=None),
    base_url: Optional[str] = Query(default=None),
    authorization: Optional[str] = Header(default=None),
) -> Dict[str, Any]:
    """
    轻量模型探测接口：
    - 返回后端默认模型（环境变量 OPENAI_MODEL 或默认值）
    - 用于 Minecraft 客户端 Settings 面板的“检测模型”按钮
    """
    # 解析 Bearer token（来自客户端 SettingsPanel 的 Authorization header）
    api_key = None
    if authorization:
        v = authorization.strip()
        if v.lower().startswith("bearer "):
            api_key = v[7:].strip()

    # provider/base_url 优先使用 query，其次用 env，再其次用默认
    class _Tmp:
        apiKey = api_key
        model = None
        llmProvider = provider
        llmBaseUrl = base_url

    cfg = build_config(_Tmp(), default_model="gpt-4o-mini")
    resolved_provider = resolve_provider(_Tmp())
    resolved_base = resolve_base_url(_Tmp(), provider=resolved_provider)
    logger.info("GET /models provider=%s base_url=%s has_auth=%s", resolved_provider, resolved_base, bool(api_key))

    # 如果用户指定了 provider/base_url（尤其是 DeepSeek/OpenAI-compatible），尝试在线拉取 /models 列表
    models_list: List[str] = []
    remote_models_ok = False
    remote_error: Optional[str] = None
    if resolved_base and api_key:
        try:
            # Hard-timeout guard: DNS/SSL may ignore socket timeout in some environments.
            with concurrent.futures.ThreadPoolExecutor(max_workers=1) as ex:
                fut = ex.submit(_fetch_remote_models, resolved_base, api_key)
                models_list = fut.result(timeout=_REMOTE_MODELS_HARD_TIMEOUT_SEC)
                remote_models_ok = True
        except concurrent.futures.TimeoutError:
            remote_models_ok = False
            remote_error = f"remote /models timeout after {_REMOTE_MODELS_HARD_TIMEOUT_SEC}s"
            logger.warning("fetch remote /models hard-timeout: provider=%s base_url=%s", resolved_provider, resolved_base)
            models_list = []
        except Exception as e:
            remote_models_ok = False
            remote_error = str(e)
            logger.warning("fetch remote /models failed: %s", str(e))
            models_list = []

    models_source = "remote" if (models_list and remote_models_ok) else "empty"

    # Provider 兜底：给 UI 一个可选的“常用模型”列表，避免刷新后完全为空
    if not models_list:
        fallback = _FALLBACK_MODELS_BY_PROVIDER.get(resolved_provider) or _FALLBACK_MODELS_BY_PROVIDER.get("openai_compat")
        if fallback:
            models_list = list(fallback)
            models_source = "fallback"

    default_model = cfg.model
    picked = pick_preferred_model(models_list, resolved_provider) if models_list else None
    if picked:
        default_model = picked

    return {
        "default_model": default_model,
        "models": models_list[:50],
        "provider": resolved_provider,
        "base_url": resolved_base,
        "models_source": models_source,
        "remote_models_ok": remote_models_ok,
        "remote_error": remote_error,
        "openai_available": HAS_OPENAI,
        "has_api_key": bool(api_key or os.getenv("OPENAI_API_KEY")),
    }

