package com.formacraft.common.assembly;

import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.ComponentStorage;
import com.formacraft.common.component.socket.continuous.*;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.tool.ToolConstraintSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * ContinuousPlacementIntegration（连续放置集成）：将 ContinuousPlacementEngine 集成到工作流中。
 */
public final class ContinuousPlacementIntegration {
    private ContinuousPlacementIntegration() {}

    /**
     * 沿路径折线放置构件。
     */
    public static List<BlockPatch> placeAlongPath(
            ToolConstraintSnapshot snapshot,
            String componentId,
            ContinuousPlacementPolicy policy
    ) {
        List<BlockPatch> allPatches = new ArrayList<>();

        if (snapshot == null || !snapshot.hasPaths() || componentId == null || policy == null) {
            return allPatches;
        }

        List<PathSocket> sockets = PathSocket.fromSnapshot(snapshot);
        if (sockets.isEmpty()) {
            return allPatches;
        }

        ComponentDefinition component = ComponentStorage.loadComponent(null, componentId);
        if (component == null) {
            return allPatches;
        }

        for (PathSocket socket : sockets) {
            List<BlockPatch> patches = ContinuousPlacementEngine.place(socket, component, policy);
            allPatches.addAll(patches);
        }

        return allPatches;
    }

    /**
     * 沿轮廓边界放置构件。
     */
    public static List<BlockPatch> placeAlongOutline(
            ToolConstraintSnapshot snapshot,
            String componentId,
            ContinuousPlacementPolicy policy
    ) {
        if (snapshot == null || !snapshot.hasOutline() || componentId == null || policy == null) {
            return List.of();
        }

        OutlineEdgeSocket socket = OutlineEdgeSocket.fromOutlineShape(snapshot.outline);
        if (socket.samplePoints(1).size() < 2) {
            return List.of();
        }

        ComponentDefinition component = ComponentStorage.loadComponent(null, componentId);
        if (component == null) {
            return List.of();
        }

        return ContinuousPlacementEngine.place(socket, component, policy);
    }

    /**
     * 根据构件角色和策略自动选择放置策略。
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

        return ContinuousPlacementPolicy.WALL_POLICY;
    }
}
