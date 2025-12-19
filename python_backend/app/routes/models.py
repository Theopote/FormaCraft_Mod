from fastapi import APIRouter
import os

from ..services.ai_planner import HAS_OPENAI

router = APIRouter()


@router.get("/models")
def models():
    """
    轻量模型探测接口：
    - 返回后端默认模型（环境变量 OPENAI_MODEL 或默认值）
    - 用于 Minecraft 客户端 Settings 面板的“检测模型”按钮
    """
    default_model = os.getenv("OPENAI_MODEL", "gpt-4o-mini")
    has_key = bool(os.getenv("OPENAI_API_KEY"))
    return {
        "default_model": default_model,
        "openai_available": HAS_OPENAI,
        "has_api_key": has_key,
    }

