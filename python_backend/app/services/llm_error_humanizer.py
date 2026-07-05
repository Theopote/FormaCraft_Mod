"""
将 LLM / Provider 原始异常转为用户可读的中文说明（余额、401、403 等）。
"""
from __future__ import annotations

import re
from typing import Optional

from ..models.request import BuildRequest


def _provider_label(req: Optional[BuildRequest]) -> str:
    if req is None:
        return "auto"
    provider = (getattr(req, "llmProvider", None) or "").strip()
    model = (getattr(req, "model", None) or "").strip()
    if provider and model:
        return f"{provider}/{model}"
    return provider or model or "auto"


def summarize_billing_or_auth_error(message: str) -> Optional[str]:
    if not message:
        return None
    s = message.lower()

    if "credit balance is too low" in s or "plans & billing" in s:
        return (
            "Anthropic（Claude）账户余额不足。"
            "请前往 console.anthropic.com 充值，或在游戏设置中更换 Provider / API Key。"
        )

    if "insufficient balance" in s or "balance is too low" in s or "error code: 402" in s:
        if "deepseek" in s:
            return "DeepSeek 余额不足（402）。请充值或更换有余额的 DeepSeek API Key。"
        return "LLM 账户余额不足（402）。请充值或更换有余额的 API Key。"

    if (
        "insufficient_quota" in s
        or "exceeded your current quota" in s
        or ("error code: 429" in s and "quota" in s)
    ):
        return "OpenAI 额度/配额不足（429）。请检查账单、组织配额，或更换有额度的 API Key。"

    if (
        "error code: 403" in s
        or ("403" in s and any(k in s for k in ("forbidden", "permission", "access denied", "not allowed")))
    ):
        return (
            "API 访问被拒绝（403）。"
            "请检查 Key 是否有该模型/端点权限，或账户是否被限制访问。"
        )

    if (
        "invalid_api_key" in s
        or "incorrect api key" in s
        or "invalid x-api-key" in s
        or ("authentication" in s and "fail" in s)
        or "error code: 401" in s
        or ("401" in s and any(k in s for k in ("unauthorized", "invalid", "authentication")))
    ):
        return (
            "API Key 无效或未授权（401）。"
            "请检查 Key 是否正确、是否过期，以及 Provider 是否与 Key 匹配。"
        )

    if "model_not_found" in s or "no such model" in s or "error code: 404" in s:
        return "模型不存在或当前 Key 无权使用（404）。请在设置中更换可用模型。"

    if "rate limit" in s or "too many requests" in s:
        return "请求过于频繁（限流）。请稍后重试或更换模型。"

    return None


def humanize_llm_exception(exc: Exception, req: Optional[BuildRequest] = None) -> str:
    raw = str(exc) if exc is not None else ""
    summary = summarize_billing_or_auth_error(raw)
    provider = _provider_label(req)
    if summary:
        return (
            f"{summary}\n"
            f"当前 LLM：{provider}\n"
            "建议：FormaCraft 设置 → LLM，核对 Provider / API Key / 模型，或切换至有余额的服务。"
        )
    if raw.startswith("LLM call failed"):
        return f"LLM 调用失败。\n当前 LLM：{provider}\n细节：{raw[:400]}"
    return raw
