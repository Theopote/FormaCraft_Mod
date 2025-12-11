from pydantic import BaseModel
from typing import Dict, Any, Optional


class CityEditRequest(BaseModel):
    """城市编辑请求"""
    cityId: str
    currentCitySpec: Dict[str, Any]  # 当前 CitySpec 的 JSON 字典
    editCommand: str  # 玩家的自然语言编辑指令
    context: Optional[Dict[str, Any]] = None  # 额外上下文信息


class BuildingEditRequest(BaseModel):
    """建筑编辑请求"""
    buildingId: str
    currentBuildingSpec: Dict[str, Any]  # 当前 BuildingSpec 的 JSON 字典
    editCommand: str  # 玩家的自然语言编辑指令
    context: Optional[Dict[str, Any]] = None  # 额外上下文信息

