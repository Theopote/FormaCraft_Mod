"""Bound synchronous planning without tying up the ASGI event loop.

Abandoning a response cannot stop a running Python thread. Capacity belongs to
the worker until it exits, so timed-out clients cannot create an unbounded queue.
"""
import asyncio
import contextvars
import math
import os
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from functools import partial

from fastapi import HTTPException, Request
from .inference_call import planning_budget


class PlanningExecutor:
    def __init__(self, max_workers: int = 4, timeout_sec: float = 595):
        if max_workers < 1 or not math.isfinite(timeout_sec) or timeout_sec <= 0:
            raise ValueError("Planning workers and timeout must be positive")
        self.timeout_sec = timeout_sec
        self._slots = threading.BoundedSemaphore(max_workers)
        self._pool = ThreadPoolExecutor(max_workers=max_workers, thread_name_prefix="build-plan")

    async def run(self, request: Request, fn, *args):
        if await request.is_disconnected():
            raise HTTPException(499, "Build request disconnected")
        if not self._slots.acquire(blocking=False):
            raise HTTPException(503, "Build planner is busy; retry later", headers={"Retry-After": "1"})

        def work():
            try:
                return context.run(partial(fn, *args))
            finally:
                self._slots.release()

        timeout = self.timeout_sec
        try:
            requested = float(getattr(request, "headers", {}).get("X-FormaCraft-Planning-Seconds", ""))
            if math.isfinite(requested) and requested > 0:
                timeout = min(timeout, requested)
        except ValueError:
            pass
        cancelled = threading.Event()
        deadline = time.monotonic() + timeout
        context = contextvars.copy_context()
        context.run(planning_budget.set, (deadline, cancelled))
        try:
            submitted = self._pool.submit(work)
        except BaseException:
            self._slots.release()
            raise
        future = asyncio.wrap_future(submitted)
        # Retrieve failures even if the response has been abandoned.
        future.add_done_callback(lambda done: None if done.cancelled() else done.exception())
        try:
            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise HTTPException(504, "Build planning exceeded the request deadline")
                await asyncio.wait({future}, timeout=min(0.1, remaining))
                if await request.is_disconnected():
                    raise HTTPException(499, "Build request disconnected")
                if time.monotonic() >= deadline:
                    raise HTTPException(504, "Build planning exceeded the request deadline")
                if future.done():
                    return future.result()
        finally:
            cancelled.set()

    def shutdown(self):
        # Running threads finish under their existing provider/call timeouts.
        self._pool.shutdown(wait=False)


planning_executor = PlanningExecutor(
    max_workers=int(os.getenv("BUILD_MAX_CONCURRENT_REQUESTS", "4")),
    timeout_sec=float(os.getenv("BUILD_REQUEST_TIMEOUT_SEC", "595")),
)
