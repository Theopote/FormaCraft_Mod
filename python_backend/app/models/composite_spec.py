from pydantic import BaseModel
from typing import List, Optional
from .building_spec import BuildingSpec


class Vec3i(BaseModel):
    """三维坐标"""
    x: int
    y: int
    z: int


class SubStructure(BaseModel):
    """子结构定义"""
    type: str  # TOWER, HOUSE, BRIDGE, WALL, etc.
    spec: BuildingSpec  # 完整的 BuildingSpec
    offset: Vec3i  # 相对于 Composite 的 Origin 的偏移


class PathSpec(BaseModel):
    """路径规格"""
    id: str = ""  # 唯一标识符，如 "main_road", "path_1"
    from_pos: Vec3i  # 起点（使用 from_pos 避免 Python 关键字冲突）
    to_pos: Vec3i    # 终点
    width: int = 3   # 道路宽度（默认 3 格）
    material: str = "minecraft:gravel"  # 道路材质
    style: str = "default"  # 道路样式：default / curved / stepped / decorated


class CompositeSpec(BaseModel):
    """复合结构规格（BuildingSpec 的集合 + 相对坐标 + 路径）"""
    structures: List[SubStructure]
    paths: Optional[List[PathSpec]] = []  # 路径列表（可选）

