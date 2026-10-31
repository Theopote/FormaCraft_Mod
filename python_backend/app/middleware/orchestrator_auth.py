"""Shared-secret gate for the FormaCraft orchestrator HTTP API.

When FORMACRAFT_ORCHESTRATOR_SECRET is set, every request must present matching
X-FormaCraft-Secret. When the client is not loopback, a secret is always required
(even if unset — reject with a clear error so operators cannot accidentally expose
an unauthenticated LLM proxy on the LAN/WAN).
"""

from __future__ import annotations

import ipaddress
import os
import secrets
from typing import Callable

from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import JSONResponse, Response


HEADER_NAME = "X-FormaCraft-Secret"
ENV_SECRET = "FORMACRAFT_ORCHESTRATOR_SECRET"


def configured_secret() -> str | None:
    raw = (os.getenv(ENV_SECRET) or "").strip()
    return raw or None


def _client_is_loopback(request: Request) -> bool:
    host = request.client.host if request.client else None
    if not host:
        # No peer info (some ASGI servers / unit harnesses): treat as local-only.
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        # Starlette/httpx TestClient reports host "testclient".
        return host.lower() in ("localhost", "localhost.localdomain", "testclient")


class OrchestratorAuthMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next: Callable) -> Response:
        secret = configured_secret()
        loopback = _client_is_loopback(request)

        if secret is None and loopback:
            return await call_next(request)

        if secret is None:
            return JSONResponse(
                status_code=401,
                content={
                    "detail": (
                        "Non-loopback clients require FORMACRAFT_ORCHESTRATOR_SECRET. "
                        "Bind to 127.0.0.1 for local-only use, or set the shared secret."
                    )
                },
            )

        provided = (request.headers.get(HEADER_NAME) or "").strip()
        if not provided or not secrets.compare_digest(provided, secret):
            return JSONResponse(
                status_code=401,
                content={"detail": f"Missing or invalid {HEADER_NAME}"},
            )
        return await call_next(request)
