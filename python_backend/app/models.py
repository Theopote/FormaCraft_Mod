from pydantic import BaseModel
from typing import List, Optional, Dict


class Origin(BaseModel):
    x: int
    y: int
    z: int


class PlayerInfo(BaseModel):
    name: str
    pos: List[int]  # [x, y, z]
    facing: str  # NORTH, SOUTH, EAST, WEST


class WorldInfo(BaseModel):
    dimension: str  # minecraft:overworld
    biome: Optional[str] = None  # minecraft:plains


class SelectionInfo(BaseModel):
    min: List[int]  # [x, y, z]
    max: List[int]  # [x, y, z]


class FormaRequestModel(BaseModel):
    player: PlayerInfo
    request: str
    world: WorldInfo
    selection: Optional[SelectionInfo] = None
    session_id: Optional[str] = None
    chat_history: Optional[List[str]] = None


# 保持向后兼容的旧请求格式
class BuildingRequestModel(BaseModel):
    prompt: str
    origin: Origin
    dimension: str
    session_id: Optional[str] = None
    chat_history: Optional[List[str]] = None


class Materials(BaseModel):
    wall: Optional[str] = None
    roof: Optional[str] = None
    floor: Optional[str] = None
    foundation: Optional[str] = None


class Features(BaseModel):
    hasWindows: bool = False
    hasStairs: bool = False
    hasDoor: bool = False
    hasRoof: bool = False
    windowCount: int = 0
    floorCount: int = 1


class BuildingSpec(BaseModel):
    """AI 响应的建筑规格数据结构（与 Java 端完全对齐）"""
    type: str  # tower, house, bridge, castle, etc.
    style: str  # medieval, modern, rustic, etc.
    height: int = 0
    radius: int = 0
    width: int = 0
    depth: int = 0
    materials: Optional[Materials] = None
    features: Optional[Features] = None
    notes: Optional[str] = None


# 保持向后兼容的旧响应格式
class StructureModel(BaseModel):
    type: str
    material: str
    towers: int
    style: str


class BuildingResponseModel(BaseModel):
    raw_response: str
    structure: StructureModel | None = None
