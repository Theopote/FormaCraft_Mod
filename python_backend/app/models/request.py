from typing import Optional
from pydantic import BaseModel


class Vec3i(BaseModel):
    x: int
    y: int
    z: int


class PlayerInfo(BaseModel):
    name: str
    pos: Vec3i
    facing: str  # NORTH / SOUTH / EAST / WEST...


class WorldContext(BaseModel):
    dimension: str
    biome: Optional[str] = None


class Selection(BaseModel):
    min: Vec3i
    max: Vec3i


class BuildRequest(BaseModel):
    """
    Minecraft → Python 的请求结构
    注意：这里没有把 BuildingSpec 放进请求里，
    请求只是"自然语言 + 世界上下文"，
    响应才是 BuildingSpec。
    """
    player: PlayerInfo
    world: WorldContext
    selection: Optional[Selection] = None
    requestText: str
    # 可选：模式（BUILD/PATCH/MODIFY_REGION），用于后端决定生成/编辑策略
    promptMode: Optional[str] = None
    # 可选：玩家原始输入（不含系统拼接）
    userMessage: Optional[str] = None
    sessionId: Optional[str] = None
    chatHistory: Optional[list[str]] = None

    # LLM 覆盖配置（优先于环境变量；不提供则使用环境变量/服务端默认）
    apiKey: Optional[str] = None
    model: Optional[str] = None
    temperature: Optional[float] = None

