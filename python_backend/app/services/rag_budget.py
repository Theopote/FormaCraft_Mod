"""RAG prompt-injection budget helpers."""
import os
from typing import Optional

from ..models.request import BuildRequest


def int_env(name: str, default: int) -> int:
    try:
        v = os.getenv(name)
        if v is None or str(v).strip() == "":
            return default
        return int(float(str(v).strip()))
    except Exception:
        return default


def resolve_rag_budget(req: Optional[BuildRequest]) -> dict:
    """
    RAG prompt-injection budget.
    Priority: request.ragBudget.* > env vars > defaults.
    """
    # defaults (conservative)
    topK = int_env("RAG_TOPK", 3)
    fewShotK = int_env("RAG_FEWSHOTK", 3)
    maxItems = int_env("RAG_MAX_ITEMS", 2)
    maxExampleChars = int_env("RAG_MAX_EXAMPLE_CHARS", 1600)
    maxChars = int_env("RAG_MAX_CHARS", 6000)

    if req is not None:
        rb = getattr(req, "ragBudget", None)
        if rb is not None:
            for k in ("topK", "fewShotK", "maxItems", "maxExampleChars", "maxChars"):
                try:
                    v = getattr(rb, k, None)
                    if v is None:
                        continue
                    iv = int(v)
                    if k == "topK":
                        topK = max(1, iv)
                    elif k == "fewShotK":
                        fewShotK = max(0, iv)
                    elif k == "maxItems":
                        maxItems = max(0, iv)
                    elif k == "maxExampleChars":
                        maxExampleChars = max(200, iv)
                    elif k == "maxChars":
                        maxChars = max(400, iv)
                except Exception:
                    pass

    return dict(
        topK=topK,
        fewShotK=fewShotK,
        maxItems=maxItems,
        maxExampleChars=maxExampleChars,
        maxChars=maxChars,
    )
