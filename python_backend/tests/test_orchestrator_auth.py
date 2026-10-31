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


def test_secret_required_when_configured(monkeypatch):
    monkeypatch.setenv(ENV_SECRET, "s3cret")
    client = TestClient(_app())
    assert client.get("/health").status_code == 401
    ok = client.get("/health", headers={HEADER_NAME: "s3cret"})
    assert ok.status_code == 200
    bad = client.get("/health", headers={HEADER_NAME: "wrong"})
    assert bad.status_code == 401


def test_models_rejects_private_base_url():
    from app.routes import models as models_route
    from app.services.url_safety import UnsafeUrlError

    with pytest.raises((ValueError, UnsafeUrlError)):
        models_route._fetch_remote_models("http://127.0.0.1:9", "")
