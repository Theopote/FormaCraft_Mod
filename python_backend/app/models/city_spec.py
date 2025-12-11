from pydantic import BaseModel, Field
from typing import List, Optional
from .building_spec import BuildingSpec
from .path_spec import PathSpec


class Point(BaseModel):
    """三维坐标点"""
    x: int
    y: int
    z: int


class Zone(BaseModel):
    """城市区块"""
    name: str
    type: str  # PLAZA / RESIDENTIAL / MARKET / WALL / GATE / INDUSTRIAL / COMMERCIAL
    radius: int
    center: Point


class StructurePlan(BaseModel):
    """建筑规划"""
    id: str  # 唯一标识符，如 "tower_1", "house_A"
    type: str  # HOUSE / TOWER / BRIDGE / WALL / CUSTOM
    spec: BuildingSpec
    offset: Point
    zone: Optional[str] = None  # 所属区块名称（可选）


class BridgePlan(BaseModel):
    """桥梁规划"""
    id: str  # 唯一标识符，如 "bridge_1", "main_bridge"
    from_pos: Point = Field(alias="from")  # 使用 from_pos 避免 Python 关键字冲突
    to_pos: Point = Field(alias="to")
    bridgeType: str  # flat / arched / suspension

    class Config:
        populate_by_name = True


class CitySpec(BaseModel):
    """城市规格"""
    cityName: str
    style: str  # MEDIEVAL / MODERN / ASIAN / FUTURISTIC
    size: str   # SMALL / MEDIUM / LARGE
    biome: str  # plains / forest / desert / etc.
    zones: List[Zone]
    structures: List[StructurePlan]
    roads: List[PathSpec]
    bridges: List[BridgePlan]

