import os

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from .middleware.orchestrator_auth import OrchestratorAuthMiddleware
from .routes import build, edit, blueprint, history, models, search, fbd_repair

app = FastAPI(title="FormaCraft Orchestrator", version="0.1.0")


@app.get("/")
def root():
    return {"ok": True, "service": "FormaCraft Orchestrator"}


@app.get("/health")
def health():
    return {"ok": True}


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
