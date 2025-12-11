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
    sessionId: Optional[str] = None
    chatHistory: Optional[list[str]] = None

