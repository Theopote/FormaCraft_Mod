from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from .routes import build, edit, blueprint, history, models

app = FastAPI(title="FormaCraft Orchestrator", version="0.1.0")

# 配置 CORS，允许 Minecraft 客户端访问
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # 调试阶段可以 *, 线上建议收紧
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# 注册路由
app.include_router(build.router)
app.include_router(edit.router)
app.include_router(blueprint.router)
app.include_router(history.router)
app.include_router(models.router)
