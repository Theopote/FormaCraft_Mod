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


def resolve_provider(req: Any = None) -> str:
    if req is not None:
        p = _norm(getattr(req, "llmProvider", None))
        if p:
            return p.lower()
    env = _norm(os.getenv("LLM_PROVIDER"))
    if env:
        return env.lower()
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
    if p == "deepseek":
        return "https://api.deepseek.com/v1"
    if p == "ollama":
        return "http://localhost:11434/v1"

    # openai/openai_compat: if unset, OpenAI SDK will use its default
    return None


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
    if provider == "deepseek":
        return _norm(os.getenv("DEEPSEEK_MODEL")) or "deepseek-chat"

    if provider == "ollama":
        return _norm(os.getenv("OLLAMA_MODEL")) or (default or "llama3.1")

    return _norm(os.getenv("OPENAI_MODEL")) or (default or "gpt-4o-mini")


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

    # Local ollama can run without a key; OpenAI SDK still wants a string sometimes.
    api_key = cfg.api_key
    if (cfg.provider == "ollama") and (not api_key):
        api_key = "ollama"

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
    p = _norm(provider).lower() if provider else "openai"
    u = _norm(base_url) or None
    k = _norm(api_key) or None
    if p == "ollama" and not k:
        k = "ollama"
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


