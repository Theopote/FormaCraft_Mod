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


class OutlineShape(BaseModel):
    """
    客户端 OutlineTool 的轮廓形状（与 Java `com.formacraft.common.buildcontext.OutlineShape` 字段对齐）。

    注意：该对象主要用于“硬约束提示词”和后续服务端裁剪；
    LLM 侧无需精确几何运算，只需遵守“不要越界”。
    """
    shapeType: str  # "polygon" / "circle"
    vertices: Optional[list[Vec3i]] = None
    center: Optional[Vec3i] = None
    radius: Optional[int] = None
    minY: Optional[int] = None
    maxY: Optional[int] = None


class ProtectedZone(BaseModel):
    """禁区/保护区（AABB，闭区间），与 Java `ProtectedZone(min,max)` 对齐。"""
    min: Vec3i
    max: Vec3i


class RagBudget(BaseModel):
    """
    P0: RAG prompt injection budget. If omitted, server env defaults apply.
    """
    topK: Optional[int] = None
    fewShotK: Optional[int] = None
    maxItems: Optional[int] = None
    maxExampleChars: Optional[int] = None
    maxChars: Optional[int] = None


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
    brushSelection: Optional[Selection] = None  # 笔刷选中区域边界（AABB）
    outline: Optional[OutlineShape] = None
    protectedZones: Optional[list[ProtectedZone]] = None
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
    # 新增：LLM Provider / Base URL（用于 DeepSeek/OpenAI-compatible/本地服务）
    llmProvider: Optional[str] = None
    llmBaseUrl: Optional[str] = None

    ragBudget: Optional[RagBudget] = None

