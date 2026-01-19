package com.formacraft.common.mass.derived;

import com.formacraft.common.component.socket.Socket;
import com.formacraft.common.component.socket.SocketType;
import com.formacraft.common.mass.BuildingMassComposition;
import com.formacraft.common.mass.MassRole;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

import java.util.*;

/**
 * RefinedSocketDeriver（细化 Socket 派生器）
 * <p>
 * 🎯 核心定位（架构校准 2026-01-14）：
 * Socket ≠ 洞
 * Socket = "允许发生变化的位置"
 * <p>
 * Socket 不直接破坏体量
 * Socket 只是给后续：Component、Block rule、AI decision 提供"入口"
 * <p>
 * 三大类 Socket 细化：
 * 1. DOOR Socket（通行）
 * 2. WINDOW Socket（采光 / 立面节奏）
 * 3. BALCONY Socket（体量外扩）
 * <p>
 * 优先级规则：DOOR > BALCONY > WINDOW
 */
public final class RefinedSocketDeriver {

    private RefinedSocketDeriver() {}

    /**
     * 从 Skeleton 列表派生细化后的 Socket
     * <p>
     * 使用 Socket 细化规则和优先级处理
     *
     * @param skeletons Skeleton 列表
     * @param composition 体量组合（用于判断规则）
     * @param massRoleMap Skeleton ID 到 MassRole 的映射（用于判断规则）
     * @param baseFloorY 地面层 Y 坐标
     * @param topFloorY 顶层 Y 坐标
     * @return 派生出的 Socket 列表
     */
    public static List<Socket> deriveRefinedSockets(
            List<MassDerivedSkeleton> skeletons,
            BuildingMassComposition composition,
            Map<String, MassRole> massRoleMap,
            int baseFloorY,
            int topFloorY
    ) {
        if (skeletons == null || skeletons.isEmpty()) {
            return List.of();
        }

        // 第一步：为每个位置收集所有可能的 Socket 类型
        Map<BlockPos, List<SocketCandidate>> candidatesByPos = new HashMap<>();

        for (MassDerivedSkeleton skeleton : skeletons) {
            MassRole massRole = massRoleMap.getOrDefault(skeleton.id, MassRole.PRIMARY);

            for (BlockPos pos : skeleton.positions) {
                List<SocketCandidate> candidates = evaluateSocketCandidates(
                        skeleton, pos, composition, massRole, baseFloorY, topFloorY
                );

                if (!candidates.isEmpty()) {
                    candidatesByPos.computeIfAbsent(pos, k -> new ArrayList<>()).addAll(candidates);
                }
            }
        }

        // 第二步：根据优先级选择最终的 Socket
        List<Socket> sockets = new ArrayList<>();

        for (Map.Entry<BlockPos, List<SocketCandidate>> entry : candidatesByPos.entrySet()) {
            BlockPos pos = entry.getKey();
            List<SocketCandidate> candidates = entry.getValue();

            // 选择优先级最高的 Socket
            SocketCandidate selected = selectHighestPriority(candidates);

            if (selected != null) {
                sockets.add(createSocket(selected, pos));
            }
        }

        return sockets;
    }

    /**
     * 评估某个位置的所有可能的 Socket 候选
     */
    private static List<SocketCandidate> evaluateSocketCandidates(
            MassDerivedSkeleton skeleton,
            BlockPos pos,
            BuildingMassComposition composition,
            MassRole massRole,
            int baseFloorY,
            int topFloorY
    ) {
        List<SocketCandidate> candidates = new ArrayList<>();

        // 检查 DOOR Socket
        if (SocketRefinementRules.DoorRules.canCreateDoorAt(skeleton, pos, baseFloorY)) {
            candidates.add(new SocketCandidate(
                    SocketRefinementRules.SocketPriority.DOOR,
                    SocketType.WALL_OPENING,
                    "door",
                    null // v1 简化：不传递尺寸信息
            ));
        }

        // 检查 BALCONY Socket
        if (SocketRefinementRules.BalconyRules.canCreateBalconyAt(skeleton, pos, composition, massRole)) {
            candidates.add(new SocketCandidate(
                    SocketRefinementRules.SocketPriority.BALCONY,
                    SocketType.EDGE_OUTER, // v1 简化：使用 EDGE_OUTER
                    "balcony",
                    null
            ));
        }

        // 检查 WINDOW Socket
        if (SocketRefinementRules.WindowRules.canCreateWindowAt(skeleton, pos, baseFloorY, topFloorY)) {
            // 还需要检查水平节奏
            int spacingOffset = 0; // v1 简化：固定偏移
            if (SocketRefinementRules.WindowRules.matchesWindowSpacing(pos, massRole, spacingOffset)) {
                candidates.add(new SocketCandidate(
                        SocketRefinementRules.SocketPriority.WINDOW,
                        SocketType.WALL_OPENING,
                        "window",
                        null // v1 简化：不传递尺寸信息
                ));
            }
        }

        return candidates;
    }

    /**
     * 选择优先级最高的 Socket
     * <p>
     * 优先级：DOOR > BALCONY > WINDOW
     */
    private static SocketCandidate selectHighestPriority(List<SocketCandidate> candidates) {
        if (candidates.isEmpty()) {
            return null;
        }

        // 按优先级排序
        candidates.sort((a, b) -> b.priority.ordinal() - a.priority.ordinal());

        // 返回优先级最高的
        return candidates.getFirst();
    }

    /**
     * 创建 Socket
     */
    private static Socket createSocket(SocketCandidate candidate, BlockPos pos) {
        Box bounds = new Box(
                pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1
        );

        Direction facing = Direction.NORTH; // v1 简化：默认朝向，未来需要从 Skeleton 获取

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
        final SocketType socketType;
        final String semanticTag;

        SocketCandidate(
                SocketRefinementRules.SocketPriority priority,
                SocketType socketType,
                String semanticTag,
                @SuppressWarnings("unused") Object sizeInfo // v1 简化：暂不使用
        ) {
            this.priority = priority;
            this.socketType = socketType;
            this.semanticTag = semanticTag;
        }
    }
}
