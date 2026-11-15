"""
请求适配器：将 Java 端的 FormaRequest JSON 转换为 Python 的 BuildRequest
因为 Java 端可能使用不同的字段名（如 requestText vs request）
"""
from typing import Optional
from pydantic import BaseModel

from .request import (
    BuildRequest, PlayerInfo, WorldContext, Selection, Vec3i, PlanOutputFormat,
    OutlineShape, ProtectedZone, PathConstraint, ReferenceInput,
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

    def _is_nested_shape(self) -> bool:
        """True when the payload uses the canonical nested BuildRequest shape.

        Java 的扁平 FormaRequest 永远不携带 ``player`` / ``world`` 字典，因此
        这两个键的存在即可安全判定为嵌套格式。旧实现仅凭 ``requestText``
        非空就切到扁平分支，会把合法的嵌套请求降级成扁平请求并丢掉
        ``player.facing`` / ``world.dimension`` / ``selection`` / ``path``。
        """
        return isinstance(self.player, dict) or isinstance(self.world, dict)

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
        """标准嵌套格式 → BuildRequest（保持 player/world/selection/path 原样）。"""
        if not self.player or not self.world:
            raise ValueError("player and world are required for nested requests")

        request_text = self.requestText if self.requestText is not None else self.request
        if not request_text:
            raise ValueError("requestText is required for nested requests")

        # 处理 player
        player_pos_data = self.player.get("pos")
        if isinstance(player_pos_data, dict):
            player_pos = Vec3i(**player_pos_data)
        elif isinstance(player_pos_data, list) and len(player_pos_data) == 3:
            player_pos = Vec3i(x=player_pos_data[0], y=player_pos_data[1], z=player_pos_data[2])
        else:
            player_pos = Vec3i(x=0, y=64, z=0)

        player_info = PlayerInfo(
            name=self.player.get("name", "Player"),
            pos=player_pos,
            facing=self.player.get("facing", "NORTH")
        )

        # 处理 world
        world_context = WorldContext(
            dimension=self.world.get("dimension", "minecraft:overworld"),
            biome=self.world.get("biome")
        )

        # 处理 selection
        selection_obj = None
        if self.selection:
            min_data = self.selection.get("min")
            max_data = self.selection.get("max")
            if isinstance(min_data, dict) and isinstance(max_data, dict):
                selection_obj = Selection(
                    min=Vec3i(**min_data),
                    max=Vec3i(**max_data)
                )
            elif isinstance(min_data, list) and isinstance(max_data, list) and \
                 len(min_data) == 3 and len(max_data) == 3:
                selection_obj = Selection(
                    min=Vec3i(x=min_data[0], y=min_data[1], z=min_data[2]),
                    max=Vec3i(x=max_data[0], y=max_data[1], z=max_data[2])
                )

        # 笔刷选区：与扁平格式保持同一语义——已有选区时，选区优先，笔刷不再单独携带。
        brush_selection_obj = None
        if self.brushSelection and not selection_obj:
            min_data = self.brushSelection.get("min")
            max_data = self.brushSelection.get("max")
            if isinstance(min_data, dict) and isinstance(max_data, dict):
                brush_selection_obj = Selection(
                    min=Vec3i(**min_data),
                    max=Vec3i(**max_data)
                )
            elif isinstance(min_data, list) and isinstance(max_data, list) and \
                 len(min_data) == 3 and len(max_data) == 3:
                brush_selection_obj = Selection(
                    min=Vec3i(x=min_data[0], y=min_data[1], z=min_data[2]),
                    max=Vec3i(x=max_data[0], y=max_data[1], z=max_data[2])
                )

        # 路径走廊
        path_obj = None
        if isinstance(self.path, dict):
            nodes_data = self.path.get("nodes")
            if isinstance(nodes_data, list) and nodes_data:
                try:
                    nodes = [
                        Vec3i(**n) if isinstance(n, dict)
                        else Vec3i(x=n[0], y=n[1], z=n[2])
                        for n in nodes_data
                    ]
                    if nodes:
                        path_obj = PathConstraint(nodes=nodes, radius=self.path.get("radius"))
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
            requestText=request_text,
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

