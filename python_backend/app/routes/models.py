from fastapi import APIRouter, Header, Query
import json
import os
import urllib.request
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

    # 如果用户指定了 provider/base_url（尤其是 DeepSeek/OpenAI-compatible），尝试在线拉取 /models 列表
    models_list: List[str] = []
    if resolved_base and api_key:
        try:
            url = resolved_base.rstrip("/") + "/models"
            req = urllib.request.Request(url, method="GET")
            req.add_header("Accept", "application/json")
            req.add_header("Authorization", f"Bearer {api_key}")
            with urllib.request.urlopen(req, timeout=2) as resp:
                body = resp.read().decode("utf-8", errors="ignore")
                data = json.loads(body)
                if isinstance(data, dict) and isinstance(data.get("data"), list):
                    for item in data["data"]:
                        if isinstance(item, dict) and isinstance(item.get("id"), str):
                            models_list.append(item["id"])
        except Exception:
            models_list = []

    # Provider 兜底：DeepSeek 的常用模型名（即便无法在线探测，也能让 UI 有可选提示）
    if not models_list and resolved_provider == "deepseek":
        models_list = ["deepseek-chat", "deepseek-reasoner"]

    default_model = cfg.model
    picked = pick_preferred_model(models_list, resolved_provider) if models_list else None
    if picked:
        default_model = picked

    return {
        "default_model": default_model,
        "models": models_list[:50],
        "provider": resolved_provider,
        "base_url": resolved_base,
        "openai_available": HAS_OPENAI,
        "has_api_key": bool(api_key or os.getenv("OPENAI_API_KEY")),
    }

