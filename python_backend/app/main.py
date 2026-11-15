import logging
import os
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from .middleware.orchestrator_auth import OrchestratorAuthMiddleware
from .routes import build, edit, blueprint, history, models, search, fbd_repair

logger = logging.getLogger(__name__)


def _verify_capability_catalog() -> None:
    """Fail fast when the authoritative capability catalog is missing.

    The exported Java catalog is a **required deployment resource**: it is the only
    definition of what the server can actually build. Booting without it would let
    the planner work against a divergent capability set, which is exactly the class
    of bug this contract exists to prevent.

    In ``strict`` mode a missing/invalid catalog aborts startup. Development/test
    modes may still use the static snapshot (see
    ``app.services.generation_capability``).
    """
    from .services.generation_capability import (
        CapabilityCatalogUnavailable,
        capability_mode,
        catalog_source_path,
        is_degraded,
        load_catalog,
    )

    try:
        catalog = load_catalog()
    except CapabilityCatalogUnavailable as exc:
        # Let the process fail loudly: a half-working backend that silently plans
        # against the wrong capability set is worse than one that refuses to start.
        logger.error("Capability catalog unavailable (mode=%s): %s", capability_mode(), exc)
        raise

    if is_degraded():
        logger.warning(
            "Running on the DEVELOPMENT-ONLY static capability snapshot; "
            "Python/Java capability tables may diverge.",
        )
    else:
        logger.info(
            "Capability catalog loaded from %s (schema=%s version=%s component_types=%d)",
            catalog_source_path(),
            catalog.get("schema"),
            catalog.get("version"),
            len(catalog.get("component_types") or {}),
        )


@asynccontextmanager
async def lifespan(_app: FastAPI):
    _verify_capability_catalog()
    yield


app = FastAPI(title="FormaCraft Orchestrator", version="0.1.0", lifespan=lifespan)


@app.get("/")
def root():
    return {"ok": True, "service": "FormaCraft Orchestrator"}


@app.get("/health")
def health():
    return {"ok": True}


@app.get("/health/capability")
def health_capability():
    """Capability-catalog deployment status.

    Reports whether the backend is running against the **authoritative** Java-exported
    catalog or the development-only static snapshot. A production deployment that
    reports ``"degraded": true`` is planning against a possibly divergent capability
    set and should be fixed (ship
    ``assets/formacraft/generation_capability_catalog.json``).
    """
    from .services.generation_capability import (
        CapabilityCatalogUnavailable,
        capability_mode,
        catalog_source_path,
        is_degraded,
        load_catalog,
    )

    try:
        catalog = load_catalog()
    except CapabilityCatalogUnavailable as exc:
        return {
            "ok": False,
            "degraded": True,
            "mode": capability_mode(),
            "error": str(exc),
        }
    return {
        "ok": True,
        "degraded": is_degraded(),
        "mode": capability_mode(),
        "source": catalog_source_path(),
        "schema": catalog.get("schema"),
        "version": catalog.get("version"),
        "component_types": len(catalog.get("component_types") or {}),
    }


@app.get("/style-packs")
def style_packs():
    from .services.user_style_packs import snapshot
    _, reports = snapshot()
    return {"packs": reports, "activation": "restart_game_and_backend"}

# Minecraft HTTP clients do not require CORS. Opt in only for browser debugging.
app.add_middleware(
    CORSMiddleware,
    allow_origins=[s.strip() for s in os.getenv("FORMACRAFT_CORS_ORIGINS", "").split(",") if s.strip()],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)
# Shared-secret gate (see FORMACRAFT_ORCHESTRATOR_SECRET). Added after CORS so it runs first.
app.add_middleware(OrchestratorAuthMiddleware)

# 注册路由
app.include_router(build.router)
app.include_router(edit.router)
app.include_router(blueprint.router)
app.include_router(history.router)
app.include_router(models.router)
app.include_router(search.router)
app.include_router(fbd_repair.router)
