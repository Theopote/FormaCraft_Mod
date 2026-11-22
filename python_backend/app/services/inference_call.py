"""Synchronous inference waiting policy, independent of planning and routing."""
import concurrent.futures
import os
import threading
from typing import Optional

from ..models.request import BuildRequest

# Bounded shared pool: timed-out work may keep a worker busy until the underlying
# call returns, but we never spawn a new OS thread per request.
# Headroom covers nested call_with_timeout (research → classify/synth/vision) and
# concurrent build requests; explicit LLM_TIMEOUT_POOL_WORKERS still wins.
def _default_timeout_pool_workers() -> int:
    try:
        concurrent = max(1, int(os.getenv("BUILD_MAX_CONCURRENT_REQUESTS", "4")))
    except Exception:
        concurrent = 4
    configured = os.getenv("LLM_TIMEOUT_POOL_WORKERS")
    if configured is not None and str(configured).strip() != "":
        try:
            return max(1, int(configured))
        except Exception:
            pass
    return max(12, 2 * concurrent + 4)


_TIMEOUT_POOL_WORKERS = _default_timeout_pool_workers()

_TIMEOUT_POOL = concurrent.futures.ThreadPoolExecutor(
    max_workers=_TIMEOUT_POOL_WORKERS,
    thread_name_prefix="llm-timeout",
)
_TIMEOUT_SLOTS = threading.BoundedSemaphore(_TIMEOUT_POOL_WORKERS)
_IN_TIMEOUT_WORKER = threading.local()


class TimeoutPoolSaturatedError(RuntimeError):
    """Raised when every timeout-guard worker is already busy."""


def call_with_timeout(fn, timeout_sec: float):
    """Bound caller waiting time; running threads are not forcibly cancelled.

    Nested calls from inside an already-guarded worker run inline (no second slot),
    so research→classify/synth cannot saturate the pool against itself.
    """
    if getattr(_IN_TIMEOUT_WORKER, "active", False):
        return fn()

    if not _TIMEOUT_SLOTS.acquire(blocking=False):
        raise TimeoutPoolSaturatedError(
            f"timeout worker pool saturated (max_workers={_TIMEOUT_POOL_WORKERS})"
        )

    def work():
        _IN_TIMEOUT_WORKER.active = True
        try:
            return fn()
        finally:
            _IN_TIMEOUT_WORKER.active = False
            _TIMEOUT_SLOTS.release()

    try:
        fut = _TIMEOUT_POOL.submit(work)
    except Exception:
        _TIMEOUT_SLOTS.release()
        raise

    try:
        return fut.result(timeout=timeout_sec)
    except concurrent.futures.TimeoutError as e:
        try:
            fut.cancel()
        except Exception:
            pass
        # Slot stays held until the still-running worker finishes.
        raise TimeoutError(f"LLM call timed out after {timeout_sec}s") from e


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
