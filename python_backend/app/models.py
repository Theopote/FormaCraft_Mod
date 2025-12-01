from pydantic import BaseModel
from typing import List, Optional


class Origin(BaseModel):
    x: int
    y: int
    z: int


class BuildingRequestModel(BaseModel):
    prompt: str
    origin: Origin
    dimension: str
    # 可选的会话 ID，用于在后端区分不同对话轮次
    session_id: Optional[str] = None
    # 可选的对话历史，按时间顺序排列的简单文本列表（例如 "Player: ...", "AI: ..."）
    chat_history: Optional[List[str]] = None


class StructureModel(BaseModel):
    type: str
    material: str
    towers: int
    style: str


class BuildingResponseModel(BaseModel):
    raw_response: str
    structure: StructureModel | None = None
