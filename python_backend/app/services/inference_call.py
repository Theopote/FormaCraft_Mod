"""Synchronous inference waiting policy, independent of planning and routing."""
import concurrent.futures
import os
from typing import Optional

from ..models.request import BuildRequest


def call_with_timeout(fn, timeout_sec: float):
    """Bound caller waiting time; running threads are not forcibly cancelled."""
    ex = concurrent.futures.ThreadPoolExecutor(max_workers=1)
    fut = None
    try:
        fut = ex.submit(fn)
        return fut.result(timeout=timeout_sec)
    except concurrent.futures.TimeoutError as e:
        if fut is not None:
            try:
                fut.cancel()
            except Exception:
                pass
        raise TimeoutError(f"LLM call timed out after {timeout_sec}s") from e
    finally:
        try:
            ex.shutdown(wait=False, cancel_futures=True)
        except Exception:
            pass



# Environment values are captured at import time, matching the existing policy.
_LLM_CALL_TIMEOUT_SEC = float(os.getenv("LLM_CALL_TIMEOUT_SEC", "45"))
_LLM_CALL_TIMEOUT_DEEPSEEK_SEC = float(os.getenv("LLM_CALL_TIMEOUT_DEEPSEEK_SEC", "60"))
_LLM_CALL_TIMEOUT_REASONER_SEC = float(os.getenv("LLM_CALL_TIMEOUT_REASONER_SEC", "120"))
_LLM_CALL_TIMEOUT_LLMPLAN_SEC = float(os.getenv("LLM_CALL_TIMEOUT_LLMPLAN_SEC", "180"))
_LLM_CALL_TIMEOUT_COMPOSITE_SEC = float(os.getenv("LLM_CALL_TIMEOUT_COMPOSITE_SEC", "600"))
_LLM_CALL_TIMEOUT_CITY_SEC = float(os.getenv("LLM_CALL_TIMEOUT_CITY_SEC", "600"))


def _resolve_timeout_sec(req: Optional[BuildRequest], model: Optional[str]) -> float:
    """
    Some providers/models are slower (e.g. deepseek-reasoner). We allow longer timeouts there.
    """
    base = _LLM_CALL_TIMEOUT_SEC
    try:
        provider = (getattr(req, "llmProvider", None) or "").strip().lower() if req is not None else ""
    except Exception:
        provider = ""
    m = (model or "").strip().lower()

    if provider == "deepseek":
        # deepseek reasoner can easily exceed 45s
        if "reasoner" in m:
            return max(base, _LLM_CALL_TIMEOUT_REASONER_SEC)
        return max(base, _LLM_CALL_TIMEOUT_DEEPSEEK_SEC)

    # default
    return base


def _resolve_timeout_sec_for_task(task: str, req: Optional[BuildRequest], model: Optional[str]) -> float:
    """
    task: building / composite / city / llmplan
    """
    t = (task or "").strip().lower()
    base = _resolve_timeout_sec(req, model)
    if t == "composite":
        return max(base, _LLM_CALL_TIMEOUT_COMPOSITE_SEC)
    if t == "city":
        return max(base, _LLM_CALL_TIMEOUT_CITY_SEC)
    if t == "llmplan":
        # LlmPlan 通常需要更长的处理时间，特别是对于复杂的 prompt 和大型 JSON 输出
        # 确保使用足够的超时时间（默认 180 秒）
        return max(base, _LLM_CALL_TIMEOUT_LLMPLAN_SEC)
    return base
