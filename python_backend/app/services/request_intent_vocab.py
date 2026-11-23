"""Load shared request_intent vocabulary (Java + Python single source)."""

from __future__ import annotations

import json
import os
from functools import lru_cache
from pathlib import Path
from typing import Any, Dict


_REPO_ROOT = Path(__file__).resolve().parents[3]
_VOCAB_PATH = (
    _REPO_ROOT
    / "src"
    / "main"
    / "resources"
    / "assets"
    / "formacraft"
    / "request_intent"
    / "request_intent_vocab_v1.json"
)


@lru_cache(maxsize=1)
def load_request_intent_vocab() -> Dict[str, Any]:
    path = Path(os.getenv("FORMACRAFT_REQUEST_INTENT_VOCAB_PATH") or _VOCAB_PATH)
    try:
        with path.open("r", encoding="utf-8") as fh:
            data = json.load(fh)
    except (OSError, ValueError) as exc:
        raise RuntimeError(f"Cannot load shared request intent vocabulary: {path}") from exc
    if not isinstance(data, dict) or data.get("schema") != "formacraft.request_intent_vocab.v1":
        raise RuntimeError(f"Invalid shared request intent vocabulary schema: {path}")
    return data
