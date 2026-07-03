"""
请求适配器：将 Java 端的 FormaRequest JSON 转换为 Python 的 BuildRequest
因为 Java 端可能使用不同的字段名（如 requestText vs request）
"""
from typing import Optional
from pydantic import BaseModel

from .request import (
    BuildRequest, PlayerInfo, WorldContext, Selection, Vec3i,
    OutlineShape, ProtectedZone, PathConstraint,
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
    outputFormat: Optional[str] = None
    userMessage: Optional[str] = None

    # Java 端可选的 LLM 覆盖配置（由客户端设置面板传入）
    apiKey: Optional[str] = None
    model: Optional[str] = None
    temperature: Optional[float] = None
    llmProvider: Optional[str] = None
    llmBaseUrl: Optional[str] = None
    
    # Python 端格式（嵌套结构，向后兼容）
    player: Optional[dict] = None
    world: Optional[dict] = None
    request: Optional[str] = None
    selection: Optional[dict] = None
    
    def to_build_request(self) -> BuildRequest:
        """转换为标准的 BuildRequest"""
        # 优先使用 Java 端的扁平格式
        if self.requestText or (self.playerPos is not None):
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
                sessionId=self.sessionId,
                chatHistory=self.chatHistory,
                apiKey=self.apiKey,
                model=self.model,
                temperature=self.temperature,
                llmProvider=self.llmProvider,
                llmBaseUrl=self.llmBaseUrl,
            )
        else:
            # Python 端格式：从嵌套结构构建
            if not self.player or not self.world or not self.request:
                raise ValueError("player, world, and request are required")
            
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
            
            return BuildRequest(
                player=player_info,
                world=world_context,
                selection=selection_obj,
                outline=self.outline,
                protectedZones=self.protectedZones,
                requestText=self.request,
                promptMode=self.promptMode,
                outputFormat=self.outputFormat,
                userMessage=self.userMessage,
                sessionId=self.sessionId,
                chatHistory=self.chatHistory,
                apiKey=self.apiKey,
                model=self.model,
                temperature=self.temperature,
                llmProvider=self.llmProvider,
                llmBaseUrl=self.llmBaseUrl,
            )

