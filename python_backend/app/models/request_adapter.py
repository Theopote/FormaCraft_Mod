"""
请求适配器：将 Java 端的 FormaRequest JSON 转换为 Python 的 BuildRequest
因为 Java 端可能使用不同的字段名（如 requestText vs request）
"""
from typing import Any, Optional
from pydantic import BaseModel, PrivateAttr, model_validator

from .request import (
    BuildRequest, PlayerInfo, WorldContext, Selection, Vec3i, PlanOutputFormat,
    OutlineShape, ProtectedZone, PathConstraint, ReferenceInput, SymmetryConstraint,
    RagBudget,
)


class FormaRequestAdapter(BaseModel):
    """
    适配 Java 端的 FormaRequest 结构
    Java 端使用扁平结构：requestText, playerPos, facing, dimension, biome, selectionMin, selectionMax
    需要转换为 Python 端的嵌套结构：player, world, selection, requestText
    """
    # Java 端格式（扁平结构）
    requestText: Optional[str] = None
    playerPos: Optional[dict] = None  # BlockPos 序列化为 {"x": 100, "y": 64, "z": 200}
    facing: Optional[str] = None
    dimension: Optional[str] = None
    biome: Optional[str] = None
    selectionMin: Optional[dict] = None  # BlockPos 序列化
    selectionMax: Optional[dict] = None  # BlockPos 序列化
    brushMin: Optional[dict] = None  # BlockPos 序列化（笔刷选中区域边界）
    brushMax: Optional[dict] = None  # BlockPos 序列化
    outline: Optional[OutlineShape] = None
    protectedZones: Optional[list[ProtectedZone]] = None
    pathNodes: Optional[list[dict]] = None  # BlockPos 列表：[{"x":..,"y":..,"z":..}]
    pathRadius: Optional[int] = None
    symmetryMode: Optional[str] = None
    symmetryAxisA: Optional[dict] = None
    symmetryAxisB: Optional[dict] = None
    sessionId: Optional[str] = None
    chatHistory: Optional[list[str]] = None
    promptMode: Optional[str] = None
    outputFormat: Optional[PlanOutputFormat] = "llmplan"
    userMessage: Optional[str] = None

    # Java 端可选的 LLM 覆盖配置（由客户端设置面板传入）
    apiKey: Optional[str] = None
    model: Optional[str] = None
    temperature: Optional[float] = None
    llmProvider: Optional[str] = None
    llmBaseUrl: Optional[str] = None
    searchProvider: Optional[str] = None
    searchApiKey: Optional[str] = None
    googleCseCx: Optional[str] = None
    references: Optional[list[ReferenceInput]] = None
    componentCatalogSummary: Optional[str] = None
    componentGroupSummary: Optional[str] = None
    nearbyMemorySummary: Optional[str] = None
    
    # Python 端格式（嵌套结构，向后兼容）
    player: Optional[dict] = None
    world: Optional[dict] = None
    request: Optional[str] = None
    selection: Optional[dict] = None
    brushSelection: Optional[dict] = None
    path: Optional[dict] = None

    # 嵌套格式的显式提示词分段（与 BuildRequest 对齐）
    systemPrompt: Optional[str] = None
    userPrompt: Optional[str] = None
    ragBudget: Optional[dict] = None

    # 嵌套格式的对称约束（标准 BuildRequest 的 symmetry 对象）。
    # 扁平 Java 字段 symmetryMode/AxisA/AxisB 走 _symmetry_constraint()；
    # 嵌套请求的 symmetry 直接由 BuildRequest 承接。
    symmetry: Optional[dict] = None

    # 原始负载快照。嵌套分支需要它，才能把整个 payload 交给
    # BuildRequest.model_validate —— 逐字段复制必然漏字段。
    _raw: Optional[dict] = PrivateAttr(default=None)

    @model_validator(mode="wrap")
    @classmethod
    def _capture_raw(cls, data: Any, handler) -> Any:
        """记住原始 payload，供嵌套分支整体校验。"""
        raw = dict(data) if isinstance(data, dict) else None
        obj = handler(data)
        if raw is not None:
            obj._raw = raw
        return obj

    def _is_nested_shape(self) -> bool:
        """True when the payload uses the canonical nested BuildRequest shape.

        Java 的扁平 FormaRequest 永远不携带 ``player`` / ``world`` 字典，因此
        这两个键的存在即可安全判定为嵌套格式。旧实现仅凭 ``requestText``
        非空就切到扁平分支，会把合法的嵌套请求降级成扁平请求并丢掉
        ``player.facing`` / ``world.dimension`` / ``selection`` / ``path``。
        """
        return isinstance(self.player, dict) or isinstance(self.world, dict)

    def _symmetry_constraint(self) -> Optional[SymmetryConstraint]:
        """SymmetryTool 约束（扁平 Java 字段 → 嵌套 BuildRequest）。

        无模式时返回 None；CUSTOM_AXIS 需要两个轴点，缺一则忽略轴点仅保留模式。
        """
        mode = (self.symmetryMode or "").strip().upper()
        if not mode or mode == "NONE":
            return None
        axis_a = None
        axis_b = None
        if isinstance(self.symmetryAxisA, dict):
            axis_a = Vec3i(**self.symmetryAxisA)
        if isinstance(self.symmetryAxisB, dict):
            axis_b = Vec3i(**self.symmetryAxisB)
        return SymmetryConstraint(mode=mode, axisA=axis_a, axisB=axis_b)

    def _rag_budget(self) -> Optional[RagBudget]:
        """RAG 检索预算（两种格式共用同一份嵌套结构）。

        ``ragBudget`` 在两种线格式里都是独立对象（顶层键），
        因此扁平/嵌套分支都必须把它透传给 BuildRequest，
        否则显式预算会退回环境变量默认值。
        """
        if not isinstance(self.ragBudget, dict):
            return None
        try:
            return RagBudget(**self.ragBudget)
        except Exception:
            return None

    def to_build_request(self) -> BuildRequest:
        """转换为标准的 BuildRequest"""
        if self._is_nested_shape():
            return self._nested_build_request()
        return self._flat_build_request()

    def _flat_build_request(self) -> BuildRequest:
        """Java 端扁平格式 → 嵌套 BuildRequest。"""
        # Java 端格式：从扁平结构构建嵌套结构
        if not self.playerPos:
            # 如果没有 playerPos，尝试从 player 中获取
            if self.player and isinstance(self.player, dict):
                player_pos_data = self.player.get("pos")
                if isinstance(player_pos_data, dict):
                    player_pos = Vec3i(**player_pos_data)
                elif isinstance(player_pos_data, list) and len(player_pos_data) == 3:
                    player_pos = Vec3i(x=player_pos_data[0], y=player_pos_data[1], z=player_pos_data[2])
                else:
                    player_pos = Vec3i(x=0, y=64, z=0)
            else:
                player_pos = Vec3i(x=0, y=64, z=0)
        else:
            # playerPos 是字典 {"x": 100, "y": 64, "z": 200}
            player_pos = Vec3i(**self.playerPos)

        player_info = PlayerInfo(
            name="Player",  # Java 端可能没有传 name
            pos=player_pos,
            facing=self.facing or "NORTH"
        )

        world_context = WorldContext(
            dimension=self.dimension or "minecraft:overworld",
            biome=self.biome
        )

        selection_obj = None
        if self.selectionMin and self.selectionMax:
            # selectionMin 和 selectionMax 是字典 {"x": 90, "y": 64, "z": 190}
            selection_obj = Selection(
                min=Vec3i(**self.selectionMin),
                max=Vec3i(**self.selectionMax)
            )

        # 笔刷选中区域（如果没有选区，则使用笔刷区域）
        brush_selection_obj = None
        if self.brushMin and self.brushMax and not selection_obj:
            brush_selection_obj = Selection(
                min=Vec3i(**self.brushMin),
                max=Vec3i(**self.brushMax)
            )

        # 路径走廊（Phase 9）
        path_obj = None
        if self.pathNodes:
            try:
                nodes = [Vec3i(**n) for n in self.pathNodes if isinstance(n, dict)]
                if nodes:
                    path_obj = PathConstraint(nodes=nodes, radius=self.pathRadius)
            except Exception:
                path_obj = None

        return BuildRequest(
            player=player_info,
            world=world_context,
            selection=selection_obj,
            brushSelection=brush_selection_obj,
            outline=self.outline,
            protectedZones=self.protectedZones,
            path=path_obj,
            symmetry=self._symmetry_constraint(),
            ragBudget=self._rag_budget(),
            requestText=self.requestText or "",
            promptMode=self.promptMode,
            outputFormat=self.outputFormat,
            userMessage=self.userMessage,
            systemPrompt=self.systemPrompt,
            userPrompt=self.userPrompt,
            sessionId=self.sessionId,
            chatHistory=self.chatHistory,
            apiKey=self.apiKey,
            model=self.model,
            temperature=self.temperature,
            llmProvider=self.llmProvider,
            llmBaseUrl=self.llmBaseUrl,
            searchProvider=self.searchProvider,
            searchApiKey=self.searchApiKey,
            googleCseCx=self.googleCseCx,
            references=self.references,
            componentCatalogSummary=self.componentCatalogSummary,
            componentGroupSummary=self.componentGroupSummary,
            nearbyMemorySummary=self.nearbyMemorySummary,
        )

    def _nested_build_request(self) -> BuildRequest:
        """标准嵌套格式 → BuildRequest（整体交给 BuildRequest 校验）。

        嵌套格式就是 BuildRequest 的规范形状，因此**不再逐字段复制**：
        复制必然漏字段（历史上就漏掉了 ``symmetry`` 与 ``ragBudget``）。
        这里把原始 payload 直接交给 ``BuildRequest.model_validate``，
        于是新增的选填字段自动生效，不需要在两处各写一遍。
        """
        if not self.player or not self.world:
            raise ValueError("player and world are required for nested requests")

        raw = self._raw if isinstance(self._raw, dict) else {}
        payload = dict(raw)

        # 兼容旧键名：``request`` 是 ``requestText`` 的历史别名。
        if not payload.get("requestText") and payload.get("request"):
            payload["requestText"] = payload["request"]
        payload.pop("request", None)

        # ``player.pos`` 允许 {x,y,z} 或 [x,y,z] 两种写法。
        player = payload.get("player")
        if isinstance(player, dict):
            pos = player.get("pos")
            if isinstance(pos, list) and len(pos) == 3:
                player = dict(player)
                player["pos"] = {"x": pos[0], "y": pos[1], "z": pos[2]}
                payload["player"] = player
            elif not isinstance(pos, dict):
                player = dict(player)
                player["pos"] = {"x": 0, "y": 64, "z": 0}
                payload["player"] = player

        # 选区/笔刷的 min/max 同样接受列表写法。
        for key in ("selection", "brushSelection"):
            box = payload.get(key)
            if not isinstance(box, dict):
                continue
            normalized = dict(box)
            changed = False
            for bound in ("min", "max"):
                value = normalized.get(bound)
                if isinstance(value, list) and len(value) == 3:
                    normalized[bound] = {"x": value[0], "y": value[1], "z": value[2]}
                    changed = True
            if changed:
                payload[key] = normalized

        # 路径节点允许 {x,y,z} 或 [x,y,z]。
        path = payload.get("path")
        if isinstance(path, dict):
            nodes = path.get("nodes")
            if isinstance(nodes, list):
                normalized_nodes = []
                for node in nodes:
                    if isinstance(node, list) and len(node) == 3:
                        normalized_nodes.append({"x": node[0], "y": node[1], "z": node[2]})
                    else:
                        normalized_nodes.append(node)
                path = dict(path)
                path["nodes"] = normalized_nodes
                payload["path"] = path

        # 选区优先：已有选区时笔刷不再单独携带（与扁平格式同一语义）。
        if payload.get("selection") is not None:
            payload.pop("brushSelection", None)

        return BuildRequest.model_validate(payload)

