"""Orchestrator shared-secret middleware."""

from __future__ import annotations

import pytest
from starlette.applications import Starlette
from starlette.responses import JSONResponse
from starlette.routing import Route
from starlette.testclient import TestClient

from app.middleware.orchestrator_auth import HEADER_NAME, OrchestratorAuthMiddleware, ENV_SECRET


async def _ok(_request):
    return JSONResponse({"ok": True})


def _app():
    app = Starlette(routes=[Route("/health", _ok), Route("/build", _ok, methods=["POST"])])
    app.add_middleware(OrchestratorAuthMiddleware)
    return app


def test_loopback_without_secret_allowed(monkeypatch):
    monkeypatch.delenv(ENV_SECRET, raising=False)
    client = TestClient(_app())
    assert client.get("/health").status_code == 200


def test_forwarded_for_without_secret_rejected(monkeypatch):
    """Reverse-proxy peers look like loopback TCP; X-Forwarded-For must force auth."""
    monkeypatch.delenv(ENV_SECRET, raising=False)
    client = TestClient(_app())
    res = client.get("/health", headers={"X-Forwarded-For": "203.0.113.10"})
    assert res.status_code == 401
    assert "FORMACRAFT_ORCHESTRATOR_SECRET" in res.json()["detail"]


def test_forwarded_header_without_secret_rejected(monkeypatch):
    monkeypatch.delenv(ENV_SECRET, raising=False)
    client = TestClient(_app())
    res = client.get("/health", headers={"Forwarded": "for=203.0.113.10;proto=https"})
    assert res.status_code == 401


def test_secret_required_when_configured(monkeypatch):
    monkeypatch.setenv(ENV_SECRET, "s3cret")
    client = TestClient(_app())
    assert client.get("/health").status_code == 401
    ok = client.get("/health", headers={HEADER_NAME: "s3cret"})
    assert ok.status_code == 200
    bad = client.get("/health", headers={HEADER_NAME: "wrong"})
    assert bad.status_code == 401
    # Proxied + secret still works
    proxied = client.get(
        "/health",
        headers={HEADER_NAME: "s3cret", "X-Forwarded-For": "203.0.113.10"},
    )
    assert proxied.status_code == 200


def test_models_rejects_private_base_url():
    from app.routes import models as models_route
    from app.services.url_safety import UnsafeUrlError

    with pytest.raises((ValueError, UnsafeUrlError)):
        models_route._fetch_remote_models("http://127.0.0.1:9", "")


def test_models_allows_loopback_for_local_provider_validation():
    from app.services.url_safety import validate_reference_url

    # Non-local probes stay blocked; local-provider probes may opt into loopback.
    validate_reference_url("http://127.0.0.1:11434/v1/models", allow_loopback=True)


def test_models_read_limited_rejects_oversized_body():
    from app.routes import models as models_route

    class _Huge:
        def __init__(self):
            self._chunks = [b"x" * 1000, b"y" * 1000]

        def read(self, n=-1):
            if not self._chunks:
                return b""
            return self._chunks.pop(0)

    with pytest.raises(ValueError, match="exceeds"):
        models_route._read_limited(_Huge(), max_bytes=1500)
