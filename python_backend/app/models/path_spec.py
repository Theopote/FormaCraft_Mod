from pydantic import BaseModel
from typing import List
from .composite_spec import Vec3i


class PathSpec(BaseModel):
    """路径规格"""
    from_pos: Vec3i  # 起点
    to_pos: Vec3i    # 终点
    width: int = 3   # 道路宽度（默认 3 格）
    material: str = "minecraft:gravel"  # 道路材质
    style: str = "default"  # 道路样式：default / curved / stepped / decorated


class PathSpecList(BaseModel):
    """路径列表（用于 CompositeSpec）"""
    paths: List[PathSpec] = []

