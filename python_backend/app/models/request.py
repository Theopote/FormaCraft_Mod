from typing import Optional
from pydantic import BaseModel


class ReferenceInput(BaseModel):
    """PR-4: 用户附带的参考图 / URL（与 Java ReferenceInput 对齐）。"""

    type: str  # image_url | image_base64 | web_url | reference_json
    content: str
    caption: Optional[str] = None

    def is_reference_json(self) -> bool:
        t = (self.type or "").strip().lower()
        if t in ("reference_json", "json", "blueprint_json"):
            return True
        c = (self.content or "").strip()
        return c.startswith("{") and ("metadata" in c or "architectural_layers" in c)

    def parsed_reference_blueprint_content(self) -> Optional[str]:
        if not self.is_reference_json():
            return None
        return (self.content or "").strip()

    def normalized_image_url(self) -> Optional[str]:
        t = (self.type or "").strip().lower()
        c = (self.content or "").strip()
        if not c:
            return None
        if t == "image_url" and c.startswith(("http://", "https://")):
            return c
        if t == "image_base64":
            return c if c.startswith("data:image") else f"data:image/jpeg;base64,{c}"
        if t == "web_url" and c.startswith(("http://", "https://")):
            lower = c.lower()
            if any(ext in lower for ext in (".jpg", ".jpeg", ".png", ".webp", ".gif")):
                return c
        return None

    def is_web_page(self) -> bool:
        return (self.type or "").strip().lower() == "web_url"


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


class PathConstraint(BaseModel):
    """路径走廊（PathTool），与 Java `FormaRequest.pathNodes/pathRadius` 对齐（Phase 9）。

    服务端硬裁剪在 Java 侧完成；此处仅用于后端感知/提示词，不参与几何运算。
    """
    nodes: Optional[list[Vec3i]] = None
    radius: Optional[int] = None


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
    path: Optional[PathConstraint] = None  # 路径走廊（Phase 9）
    requestText: str
    # 可选：模式（BUILD/PATCH/MODIFY_REGION），用于后端决定生成/编辑策略
    promptMode: Optional[str] = None
    # 可选：输出格式（"llmplan" | "buildingspec" | "auto"）
    # - "llmplan": 强制使用 LlmPlan 格式
    # - "buildingspec": 强制使用 BuildingSpec 格式
    # - "auto": 自动决定（默认，基于 promptMode 和 requestText）
    outputFormat: Optional[str] = None
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

    # PR-4: 参考图 / 网页链接
    references: Optional[list[ReferenceInput]] = None

