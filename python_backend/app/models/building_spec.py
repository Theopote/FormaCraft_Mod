from enum import Enum
from typing import Optional, Dict, Any
from pydantic import BaseModel


class BuildingType(str, Enum):
    HOUSE = "HOUSE"
    TOWER = "TOWER"
    BRIDGE = "BRIDGE"
    CASTLE = "CASTLE"
    WALL = "WALL"
    CUSTOM = "CUSTOM"


class BuildingStyle(str, Enum):
    MEDIEVAL = "MEDIEVAL"
    MODERN = "MODERN"
    ASIAN = "ASIAN"
    FUTURISTIC = "FUTURISTIC"
    RUSTIC = "RUSTIC"
    DEFAULT = "DEFAULT"


class Footprint(BaseModel):
    shape: str = "rectangle"  # rectangle / circle / polygon
    width: Optional[int] = None
    depth: Optional[int] = None
    radius: Optional[int] = None


class Materials(BaseModel):
    wall: str = "minecraft:stone"
    roof: str = "minecraft:oak_planks"
    floor: str = "minecraft:oak_planks"
    window: str = "minecraft:glass_pane"
    foundation: Optional[str] = None  # 可选，用于桥梁等


class Features(BaseModel):
    hasWindows: bool = True
    hasStairs: bool = True
    hasDoor: bool = True
    hasBalcony: bool = False
    hasRoof: bool = True
    hasRoofDecoration: bool = False
    windowCount: int = 0
    floorCount: int = 1


class StyleOptions(BaseModel):
    """建筑风格选项（BuildingSpec 2.0）"""
    doorStyle: str = "single"       # single / double / arched / none
    roofType: str = "flat"          # flat / gable / cone / pyramid / hipped
    bridgeType: str = "flat"        # flat / arched / suspension / beam / rope
    windowRatio: float = 0.3        # 0.0 ~ 1.0
    windowStyle: str = "pane"       # pane / fence / stained
    wallPattern: str = "uniform"    # uniform / striped / gradient / random


class BuildingSpec(BaseModel):
    type: BuildingType
    style: BuildingStyle = BuildingStyle.DEFAULT
    footprint: Footprint
    height: int = 10
    floors: int = 1
    materials: Materials
    features: Features
    styleOptions: StyleOptions = StyleOptions()  # 风格选项（BuildingSpec 2.0）
    notes: Optional[str] = None
    # 额外字段，方便将来扩展（和 Java 的 Map<String,Object> 对齐）
    # 保留用于向后兼容，但推荐使用 styleOptions
    extra: Optional[Dict[str, Any]] = None

