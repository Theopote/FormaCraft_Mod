"""
LLM client factory:
- Supports OpenAI + OpenAI-compatible providers (DeepSeek, OpenRouter, LM Studio, vLLM, Ollama OpenAI-compat, etc.)
- Keeps backwards compatibility with OPENAI_API_KEY / OPENAI_MODEL.
"""

from __future__ import annotations

import json
import os
from urllib.parse import urlparse
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Tuple

try:
    from openai import OpenAI
    HAS_OPENAI = True
except Exception:
    HAS_OPENAI = False
    OpenAI = None


@dataclass(frozen=True)
class LlmConfig:
    provider: str
    base_url: Optional[str]
    api_key: Optional[str]
    model: str


def _norm(s: Optional[str]) -> str:
    return (s or "").strip()


# ---- 统一 Provider 目录（与 Java 侧 SettingsBaseUrlPresets.CATALOG 对齐）----
# 绝大多数供应商都提供 OpenAI 兼容端点，因此统一走 OpenAI SDK + base_url 即可覆盖。
_PROVIDER_BASE_URLS: Dict[str, Optional[str]] = {
    "openai": None,  # 用 OpenAI SDK 默认
    "openai_compat": None,
    "auto": None,
    "deepseek": "https://api.deepseek.com/v1",
    "gemini": "https://generativelanguage.googleapis.com/v1beta/openai",
    "anthropic": "https://api.anthropic.com/v1",
    "openrouter": "https://openrouter.ai/api/v1",
    "groq": "https://api.groq.com/openai/v1",
    "mistral": "https://api.mistral.ai/v1",
    "xai": "https://api.x.ai/v1",
    "together": "https://api.together.xyz/v1",
    "moonshot": "https://api.moonshot.cn/v1",
    "zhipu": "https://open.bigmodel.cn/api/paas/v4",
    "qwen": "https://dashscope.aliyuncs.com/compatible-mode/v1",
    "siliconflow": "https://api.siliconflow.cn/v1",
    "ollama": "http://localhost:11434/v1",
    "lmstudio": "http://127.0.0.1:1234/v1",
    "vllm": "http://localhost:8000/v1",
    "llamacpp": "http://localhost:8080/v1",
}

_KNOWN_PROVIDERS = set(_PROVIDER_BASE_URLS.keys())

# 本地部署（可不带 API Key）。
_LOCAL_PROVIDERS = {"ollama", "lmstudio", "vllm", "llamacpp", "local", "localai"}

# 每个 provider 的合理默认模型（仅在请求/环境未显式指定时使用）。
_PROVIDER_DEFAULT_MODEL: Dict[str, str] = {
    "openai": "gpt-4o-mini",
    "openai_compat": "gpt-4o-mini",
    "auto": "gpt-4o-mini",
    "deepseek": "deepseek-chat",
    "gemini": "gemini-2.0-flash",
    "anthropic": "claude-3-5-sonnet-latest",
    "openrouter": "openai/gpt-4o-mini",
    "groq": "llama-3.1-8b-instant",
    "mistral": "mistral-small-latest",
    "xai": "grok-2-latest",
    "together": "meta-llama/Llama-3.1-8B-Instruct-Turbo",
    "moonshot": "moonshot-v1-8k",
    "zhipu": "glm-4-flash",
    "qwen": "qwen-plus",
    "siliconflow": "Qwen/Qwen2.5-7B-Instruct",
    "ollama": "llama3.1",
    "lmstudio": "local-model",
    "vllm": "local-model",
    "llamacpp": "local-model",
}

_PROVIDER_ALIASES = {
    "penai": "openai", "opena1": "openai", "open-ai": "openai", "open_ai": "openai",
    "google": "gemini", "googleai": "gemini", "google_ai": "gemini", "gemini-openai": "gemini",
    "claude": "anthropic",
    "grok": "xai", "x.ai": "xai", "x-ai": "xai",
    "kimi": "moonshot",
    "glm": "zhipu", "bigmodel": "zhipu", "zhipuai": "zhipu",
    "dashscope": "qwen", "tongyi": "qwen", "qwq": "qwen",
    "lm-studio": "lmstudio", "lm_studio": "lmstudio",
    "llama-cpp": "llamacpp", "llama.cpp": "llamacpp", "llama_cpp": "llamacpp",
}


def _normalize_provider_id(p: str) -> str:
    """把用户输入的 provider 归一化到已知 id；未知 → openai_compat（安全默认）。"""
    p = (p or "").strip().lower()
    if not p:
        return ""
    p = _PROVIDER_ALIASES.get(p, p)
    return p if p in _KNOWN_PROVIDERS else "openai_compat"


def is_local_provider(provider: Optional[str]) -> bool:
    return (provider or "").strip().lower() in _LOCAL_PROVIDERS


def resolve_provider(req: Any = None) -> str:
    if req is not None:
        p = _norm(getattr(req, "llmProvider", None))
        if p:
            return _normalize_provider_id(p)
    env = _norm(os.getenv("LLM_PROVIDER"))
    if env:
        return _normalize_provider_id(env)
    return "openai"  # backwards-compatible default


def resolve_base_url(req: Any = None, provider: Optional[str] = None) -> Optional[str]:
    if req is not None:
        u = _norm(getattr(req, "llmBaseUrl", None))
        if u:
            try:
                p = urlparse(u)
                if p.scheme in ("http", "https") and p.netloc:
                    return u.rstrip("/")
            except Exception:
                pass
            return None

    env = _norm(os.getenv("LLM_BASE_URL")) or _norm(os.getenv("OPENAI_BASE_URL"))
    if env:
        try:
            p = urlparse(env)
            if p.scheme in ("http", "https") and p.netloc:
                return env.rstrip("/")
        except Exception:
            pass

    p = (provider or "openai").lower()
    # 已知 provider 用其默认端点；openai/openai_compat/auto 返回 None，交给 OpenAI SDK 默认。
    return _PROVIDER_BASE_URLS.get(p, None)


def resolve_api_key(req: Any = None) -> Optional[str]:
    if req is not None:
        k = _norm(getattr(req, "apiKey", None))
        if k:
            return k
    env_key = _norm(os.getenv("OPENAI_API_KEY"))
    return env_key if env_key else None


def resolve_model(req: Any = None, default: Optional[str] = None) -> str:
    if req is not None:
        m = _norm(getattr(req, "model", None))
        if m:
            return m

    provider = resolve_provider(req)

    # 向后兼容的按 provider 环境变量覆盖。
    if provider == "deepseek":
        env = _norm(os.getenv("DEEPSEEK_MODEL"))
        if env:
            return env
    elif provider == "ollama":
        env = _norm(os.getenv("OLLAMA_MODEL"))
        if env:
            return env

    # OpenAI 家族仍尊重 OPENAI_MODEL。
    if provider in ("openai", "openai_compat", "auto"):
        env = _norm(os.getenv("OPENAI_MODEL"))
        if env:
            return env
        return default or "gpt-4o-mini"

    # 其它 provider：用目录里的合理默认模型。
    dm = _PROVIDER_DEFAULT_MODEL.get(provider)
    if dm:
        return dm
    return default or _norm(os.getenv("OPENAI_MODEL")) or "gpt-4o-mini"


def build_config(req: Any = None, default_model: Optional[str] = None) -> LlmConfig:
    provider = resolve_provider(req)
    base_url = resolve_base_url(req, provider=provider)
    api_key = resolve_api_key(req)
    model = resolve_model(req, default=default_model)
    return LlmConfig(provider=provider, base_url=base_url, api_key=api_key, model=model)


def get_client(req: Any = None) -> Optional[OpenAI]:
    if not HAS_OPENAI:
        return None

    cfg = build_config(req)

    # 本地供应商（Ollama/LM Studio/vLLM/llama.cpp）可无 key；OpenAI SDK 仍要求一个非空字符串。
    api_key = cfg.api_key
    if is_local_provider(cfg.provider) and (not api_key):
        api_key = "local"

    if not api_key:
        return None

    try:
        if cfg.base_url:
            return OpenAI(api_key=api_key, base_url=cfg.base_url)
        return OpenAI(api_key=api_key)
    except Exception:
        return None


def get_client_from_fields(api_key: Optional[str], provider: Optional[str], base_url: Optional[str]) -> Optional[OpenAI]:
    if not HAS_OPENAI:
        return None
    p = _normalize_provider_id(provider) if provider else "openai"
    u = _norm(base_url) or None
    # 本地供应商未显式给 base_url 时，用目录里的默认端点，避免打到 OpenAI 官方。
    if not u and is_local_provider(p):
        u = _PROVIDER_BASE_URLS.get(p)
    k = _norm(api_key) or None
    if is_local_provider(p) and not k:
        k = "local"
    if not k:
        return None
    try:
        if u:
            return OpenAI(api_key=k, base_url=u)
        return OpenAI(api_key=k)
    except Exception:
        return None


def pick_preferred_model(ids: List[str], provider: str) -> Optional[str]:
    if not ids:
        return None
    uniq: List[str] = []
    seen = set()
    for i in ids:
        t = _norm(i)
        if not t or t in seen:
            continue
        seen.add(t)
        uniq.append(t)
    if not uniq:
        return None

    p = (provider or "openai").lower()
    if p == "deepseek":
        for cand in ("deepseek-chat", "deepseek-reasoner"):
            if cand in uniq:
                return cand
        return uniq[0]

    for cand in ("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "gpt-4.1", "gpt-4"):
        if cand in uniq:
            return cand
    for cand in ("gpt-4o-mini", "gpt-4o", "gpt-4"):
        for m in uniq:
            if m.startswith(cand):
                return m
    return uniq[0]


