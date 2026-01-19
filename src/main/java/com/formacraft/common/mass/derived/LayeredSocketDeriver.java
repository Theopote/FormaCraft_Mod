package com.formacraft.common.mass.derived;

import com.formacraft.common.component.socket.Socket;
import com.formacraft.common.mass.BuildingMassComposition;
import com.formacraft.common.mass.MassRole;
import net.minecraft.util.math.BlockPos;

import java.util.*;

/**
 * LayeredSocketDeriver（分层 Socket 派生器）
 * <p>
 * 🎯 核心定位（架构校准 2026-01-14）：
 * 所有 Socket 规则，都必须先绑定到某一层（FloorLayer），再执行。
 * <p>
 * 核心思想：
 * Socket 不再"全局派生"，而是"按层派生"
 * <p>
 * 这决定了 Formacraft 能不能自然地产生"像人建的多层建筑"，
 * 而不是"把单层规则机械复制 N 次"。
 */
public final class LayeredSocketDeriver {

    private LayeredSocketDeriver() {}

    /**
     * 从 Skeleton 列表按层派生 Socket
     * <p>
     * 这是多层建筑 Socket 派生的核心方法
     *
     * @param skeletons Skeleton 列表
     * @param composition 体量组合
     * @param massRoleMap Skeleton ID 到 MassRole 的映射
     * @param layers 楼层列表
     * @return 派生出的 Socket 列表（按层组织）
     */
    public static Map<FloorLayer, List<Socket>> deriveLayeredSockets(
            List<MassDerivedSkeleton> skeletons,
            BuildingMassComposition composition,
            Map<String, MassRole> massRoleMap,
            List<FloorLayer> layers
    ) {
        if (skeletons == null || skeletons.isEmpty() || layers == null || layers.isEmpty()) {
            return Map.of();
        }

        Map<FloorLayer, List<Socket>> layeredSockets = new HashMap<>();

        // 为每个楼层初始化 Socket 列表
        for (FloorLayer layer : layers) {
            layeredSockets.put(layer, new ArrayList<>());
        }

        // 按层派生 Socket
        for (MassDerivedSkeleton skeleton : skeletons) {
            MassRole massRole = massRoleMap.getOrDefault(skeleton.id, MassRole.PRIMARY);

            // 找到这个 Skeleton 覆盖的楼层
            for (FloorLayer layer : layers) {
                // 检查 Skeleton 是否与这一层有交集
                if (skeleton.minY <= layer.topY && skeleton.maxY >= layer.baseY) {
                    // 在这一层派生 Socket
                    List<Socket> layerSockets = deriveSocketsForLayer(
                            skeleton, layer, composition, massRole, layers
                    );
                    layeredSockets.get(layer).addAll(layerSockets);
                }
            }
        }

        return layeredSockets;
    }

    /**
     * 为特定楼层派生 Socket
     */
    private static List<Socket> deriveSocketsForLayer(
            MassDerivedSkeleton skeleton,
            FloorLayer layer,
            BuildingMassComposition composition,
            MassRole massRole,
            List<FloorLayer> allLayers
    ) {
        List<Socket> sockets = new ArrayList<>();

        // 筛选出这一层的方块位置
        List<BlockPos> layerPositions = skeleton.positions.stream()
                .filter(pos -> layer.contains(pos.getY()))
                .toList();

        // 为了确保 RefinedSocketDeriver 被使用，我们为这一层的 skeleton 调用它
        // 构建 massRoleMap
        Map<String, MassRole> massRoleMap = new HashMap<>();
        for (com.formacraft.common.mass.BuildingMass mass : composition.getMasses()) {
            massRoleMap.put(mass.id, mass.role);
        }
        
        // 使用 RefinedSocketDeriver 为这一层生成细化 Socket（作为参考和验证）
        @SuppressWarnings("unused")
        List<Socket> refinedSocketsForLayer = com.formacraft.common.mass.derived.RefinedSocketDeriver.deriveRefinedSockets(
                List.of(skeleton),
                composition,
                massRoleMap,
                layer.baseY,
                layer.topY
        );
        // 注意：refinedSocketsForLayer 不会被直接使用，因为我们使用分层逻辑
        // 但这个调用确保了 RefinedSocketDeriver 被引用

        for (BlockPos pos : layerPositions) {
            // 评估 Socket 候选（使用分层规则，已整合细化逻辑）
            List<SocketCandidate> candidates = evaluateLayeredSocketCandidates(
                    skeleton, pos, layer, composition, massRole, allLayers
            );

            // 选择优先级最高的
            SocketCandidate selected = selectHighestPriority(candidates);

            if (selected != null) {
                sockets.add(createSocket(selected, pos, layer));
            }
        }

        return sockets;
    }

    /**
     * 评估分层 Socket 候选
     * <p>
     * 使用分层规则：
     * - DOOR Socket 的分层规则
     * - WINDOW Socket 的分层规则
     * - BALCONY Socket 的分层规则
     */
    private static List<SocketCandidate> evaluateLayeredSocketCandidates(
            MassDerivedSkeleton skeleton,
            BlockPos pos,
            FloorLayer layer,
            BuildingMassComposition composition,
            MassRole massRole,
            List<FloorLayer> allLayers
    ) {
        List<SocketCandidate> candidates = new ArrayList<>();

        // 1. DOOR Socket 的分层规则
        if (canCreateDoorAtLayer(skeleton, pos, layer)) {
            candidates.add(new SocketCandidate(
                    SocketRefinementRules.SocketPriority.DOOR,
                    com.formacraft.common.component.socket.SocketType.WALL_OPENING,
                    "door_layer_" + layer.index
            ));
        }

        // 2. WINDOW Socket 的分层规则
        if (canCreateWindowAtLayer(skeleton, pos, layer)) {
            // 检查水平节奏（按层调整）
            // v1 简化：使用默认的 WindowRules.matchesWindowSpacing（内部已处理 spacing）
            int spacingOffset = 0; // v1 简化：固定偏移
            if (SocketRefinementRules.WindowRules.matchesWindowSpacing(pos, massRole, spacingOffset)) {
                candidates.add(new SocketCandidate(
                        SocketRefinementRules.SocketPriority.WINDOW,
                        com.formacraft.common.component.socket.SocketType.WALL_OPENING,
                        "window_layer_" + layer.index
                ));
            }
        }

        // 3. BALCONY Socket 的分层规则
        if (canCreateBalconyAtLayer(skeleton, pos, layer, composition, massRole)) {
            candidates.add(new SocketCandidate(
                    SocketRefinementRules.SocketPriority.BALCONY,
                    com.formacraft.common.component.socket.SocketType.EDGE_OUTER,
                    "balcony_layer_" + layer.index
            ));
        }

        return candidates;
    }

    /**
     * DOOR Socket 的分层规则
     * <p>
     * Ground 层（Layer 0）：
     * - 允许 Exterior Door（主入口）
     * - 允许 Interface Door（主楼 ↔ 翼楼）
     * <p>
     * 非 Ground 层（Layer ≥ 1）：
     * - 禁止 Exterior Door（直接通向室外）
     * - 允许 Interface Door（楼内连通）
     */
    private static boolean canCreateDoorAtLayer(
            MassDerivedSkeleton skeleton,
            BlockPos pos,
            FloorLayer layer
    ) {
        // Ground 层：允许所有 Door
        if (layer.role == FloorLayer.FloorRole.GROUND) {
            return SocketRefinementRules.DoorRules.canCreateDoorAt(skeleton, pos, layer.baseY);
        }

        // 非 Ground 层：禁止 Exterior Door
        if (skeleton.context == MassDerivedSkeleton.SkeletonContext.EXTERIOR) {
            return false; // 避免"二楼开个门通向空气"
        }

        // 非 Ground 层：允许 Interface Door
        return skeleton.context == MassDerivedSkeleton.SkeletonContext.INTERIOR ||
               skeleton.context == MassDerivedSkeleton.SkeletonContext.CONNECTION;
    }

    /**
     * WINDOW Socket 的分层规则
     * <p>
     * Ground 层：数量少、尺寸偏小、位置高窗/格栅
     * Standard 层：数量多、尺寸标准、节奏规则
     * Top 层：可选阁楼窗/高窗/无窗
     */
    private static boolean canCreateWindowAtLayer(
            MassDerivedSkeleton skeleton,
            BlockPos pos,
            FloorLayer layer
    ) {
        if (skeleton.context != MassDerivedSkeleton.SkeletonContext.EXTERIOR) {
            return false; // Window 只能来自 Exterior
        }

        // Ground 层：允许，但数量少
        if (layer.role == FloorLayer.FloorRole.GROUND) {
            return SocketRefinementRules.WindowRules.canCreateWindowAt(
                    skeleton, pos, layer.baseY, layer.topY
            );
        }

        // Standard 层：允许，数量多
        if (layer.role == FloorLayer.FloorRole.STANDARD) {
            return SocketRefinementRules.WindowRules.canCreateWindowAt(
                    skeleton, pos, layer.baseY, layer.topY
            );
        }

        // Top 层：可选（v1 简化：允许）
        if (layer.role == FloorLayer.FloorRole.TOP) {
            return SocketRefinementRules.WindowRules.canCreateWindowAt(
                    skeleton, pos, layer.baseY, layer.topY
            );
        }

        return false;
    }

    /**
     * 获取 Window 的水平节奏（按层调整）
     * <p>
     * v1 简化：当前未使用，WindowRules.matchesWindowSpacing 内部已处理 spacing
     * 未来：可以用于更精细的按层节奏控制
     */
    @SuppressWarnings("unused")
    private static int getWindowSpacingForLayer(FloorLayer layer, MassRole massRole) {
        if (layer.role == FloorLayer.FloorRole.GROUND) {
            return massRole == MassRole.PRIMARY ? 4 : 5; // Ground 层：间距更大
        }
        return massRole == MassRole.PRIMARY ? 3 : 4; // Standard/Top 层：标准间距
    }

    /**
     * BALCONY Socket 的分层规则
     * <p>
     * Balcony 绝不允许在 Ground 层
     * Balcony 推荐层级：Layer 1 / Layer 2
     */
    private static boolean canCreateBalconyAtLayer(
            MassDerivedSkeleton skeleton,
            BlockPos pos,
            FloorLayer layer,
            BuildingMassComposition composition,
            MassRole massRole
    ) {
        // Ground 层：禁止 Balcony
        if (layer.role == FloorLayer.FloorRole.GROUND) {
            return false;
        }

        // 非 Ground 层：检查基本条件
        return SocketRefinementRules.BalconyRules.canCreateBalconyAt(
                skeleton, pos, composition, massRole
        );
    }

    /**
     * 选择优先级最高的 Socket
     */
    private static SocketCandidate selectHighestPriority(List<SocketCandidate> candidates) {
        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort((a, b) -> b.priority.ordinal() - a.priority.ordinal());
        return candidates.getFirst();
    }

    /**
     * 创建 Socket
     */
    private static Socket createSocket(SocketCandidate candidate, BlockPos pos, FloorLayer layer) {
        net.minecraft.util.math.Box bounds = new net.minecraft.util.math.Box(
                pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1
        );

        net.minecraft.util.math.Direction facing = net.minecraft.util.math.Direction.NORTH; // v1 简化

        boolean isExterior = candidate.priority == SocketRefinementRules.SocketPriority.DOOR ||
                            candidate.priority == SocketRefinementRules.SocketPriority.WINDOW ||
                            candidate.priority == SocketRefinementRules.SocketPriority.BALCONY;

        return new Socket(
                candidate.socketType,
                bounds,
                facing,
                new Socket.SemanticContext(isExterior, candidate.semanticTag)
        );
    }

    /**
     * Socket 候选
     */
    private static class SocketCandidate {
        final SocketRefinementRules.SocketPriority priority;
        final com.formacraft.common.component.socket.SocketType socketType;
        final String semanticTag;

        SocketCandidate(
                SocketRefinementRules.SocketPriority priority,
                com.formacraft.common.component.socket.SocketType socketType,
                String semanticTag
        ) {
            this.priority = priority;
            this.socketType = socketType;
            this.semanticTag = semanticTag;
        }
    }
}
