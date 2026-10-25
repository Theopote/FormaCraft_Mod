"""Synchronous inference waiting policy, independent of planning and routing."""
import concurrent.futures


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


