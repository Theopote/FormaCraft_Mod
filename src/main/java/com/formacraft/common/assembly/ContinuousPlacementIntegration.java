package com.formacraft.common.assembly;

import com.formacraft.client.tool.OutlineTool;
import com.formacraft.client.tool.PathTool;
import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.ComponentStorage;
import com.formacraft.common.component.socket.continuous.*;
import com.formacraft.common.patch.BlockPatch;

import java.util.ArrayList;
import java.util.List;

/**
 * ContinuousPlacementIntegration（连续放置集成）：将 ContinuousPlacementEngine 集成到工作流中。
 * <p>
 * 这个类提供了便捷的方法，从工具状态创建 ContinuousSocket 并执行连续放置。
 */
public final class ContinuousPlacementIntegration {
    private ContinuousPlacementIntegration() {}

    /**
     * 从 PathTool 创建连续插槽并放置构件
     * 
     * @param pathTool PathTool 实例
     * @param componentId 构件 ID
     * @param policy 放置策略
     * @return BlockPatch 列表
     */
    public static List<BlockPatch> placeAlongPath(
            PathTool pathTool,
            String componentId,
            ContinuousPlacementPolicy policy
    ) {
        List<BlockPatch> allPatches = new ArrayList<>();

        if (pathTool == null || componentId == null || policy == null) {
            return allPatches;
        }

        // 从 PathTool 创建连续插槽
        List<PathSocket> sockets = PathSocket.fromPathTool(pathTool);
        if (sockets.isEmpty()) {
            return allPatches;
        }

        // 加载构件
        ComponentDefinition component = ComponentStorage.loadComponent(null, componentId);
        if (component == null) {
            return allPatches;
        }

        // 沿每个路径放置
        for (PathSocket socket : sockets) {
            List<BlockPatch> patches = ContinuousPlacementEngine.place(
                    socket, component, policy
            );
            allPatches.addAll(patches);
        }

        return allPatches;
    }

    /**
     * 从 OutlineTool 创建连续插槽并放置构件
     * 
     * @param outlineTool OutlineTool 实例
     * @param componentId 构件 ID
     * @param policy 放置策略
     * @return BlockPatch 列表
     */
    public static List<BlockPatch> placeAlongOutline(
            OutlineTool outlineTool,
            String componentId,
            ContinuousPlacementPolicy policy
    ) {
        if (outlineTool == null || !outlineTool.hasShape() || componentId == null || policy == null) {
            return List.of();
        }

        // 从 OutlineTool 创建连续插槽
        OutlineEdgeSocket socket = OutlineEdgeSocket.fromOutlineTool(outlineTool.getShape());
        if (socket.samplePoints(1).size() < 2) {
            return List.of();
        }

        // 加载构件
        ComponentDefinition component = ComponentStorage.loadComponent(null, componentId);
        if (component == null) {
            return List.of();
        }

        // 沿轮廓放置
        return ContinuousPlacementEngine.place(socket, component, policy);
    }

    /**
     * 根据构件角色和策略自动选择放置策略
     * 
     * @param role 构件角色（例如："wall", "railing", "column"）
     * @return ContinuousPlacementPolicy
     */
    public static ContinuousPlacementPolicy selectPolicyByRole(String role) {
        if (role == null) {
            return ContinuousPlacementPolicy.WALL_POLICY;
        }

        String lower = role.toLowerCase();
        if (lower.contains("wall") || lower.contains("fort")) {
            return ContinuousPlacementPolicy.WALL_POLICY;
        } else if (lower.contains("rail") || lower.contains("fence")) {
            return ContinuousPlacementPolicy.RAILING_POLICY;
        } else if (lower.contains("column") || lower.contains("colonnade") || lower.contains("corridor")) {
            return ContinuousPlacementPolicy.COLONNADE_POLICY;
        } else if (lower.contains("great") && lower.contains("wall")) {
            return ContinuousPlacementPolicy.GREAT_WALL_POLICY;
        }

        return ContinuousPlacementPolicy.WALL_POLICY; // 默认
    }
}
