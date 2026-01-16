package com.formacraft.common.component.socket;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.List;

/**
 * SocketFinder（Socket 查找器）v1：在世界/选区中查找可用的 Provider Socket。
 * <p>
 * 职责：
 * - 扫描世界中的"已放置组件"或"Skeleton 节点"
 * - 提取它们的 Provider Socket
 * - 过滤出与目标 Consumer Socket 匹配的
 * - 返回 SocketPlacement 列表（供 AI/Tool 选择）
 * <p>
 * v1 策略（最小可用）：
 * - 从"已放置组件"的 Instance 中读取 Socket
 * - 从"Skeleton 节点"中读取 Socket（v2 扩展）
 * - 从"选区边界"启发式推断 Socket（v2 扩展）
 * <p>
 * 注意：
 * - v1 实现仅提供接口骨架，具体扫描逻辑需要与 Component Instance Storage 集成
 * - 为避免循环依赖，这里只做"接口定义"，实际实现在 server 包中
 */
public final class SocketFinder {
    private SocketFinder() {}

    /**
     * 在指定选区内查找所有匹配的 Provider Socket（核心方法）。
     * <p>
     * v1 实现：
     * - 返回空列表（需要与 Component Instance Storage / Skeleton System 集成）
     * - v2 补齐实现
     * <p>
     * 参数：
     * - world：世界实例
     * - searchBox：搜索范围（世界坐标）
     * - consumer：目标 Consumer Socket（用于匹配过滤）
     * <p>
     * 返回：
     * - List<SocketPlacement>：所有匹配的放置位置
     */
    public static List<SocketPlacement> findProviders(
            ServerWorld world,
            Box searchBox,
            ComponentSocket consumer
    ) {
        // v1 骨架：返回空列表（避免循环依赖）
        // v2 实现：
        // 1. 扫描 searchBox 内的所有 Component Instance
        // 2. 读取它们的 sockets（来自 Prototype）
        // 3. 过滤 PROVIDER 且与 consumer 匹配的
        // 4. 计算世界坐标（origin = instance.anchor + socket.offset）
        // 5. 返回 SocketPlacement 列表
        return new ArrayList<>();
    }

    /**
     * 根据 socket id 查找（快速定位）。
     * <p>
     * v1 骨架：返回空列表
     */
    public static List<SocketPlacement> findBySocketId(
            ServerWorld world,
            Box searchBox,
            String socketId
    ) {
        // v1 骨架：返回空列表
        return new ArrayList<>();
    }

    /**
     * 启发式推断：从选区边界/面推断可能的 Socket。
     * <p>
     * 例如：
     * - 选区是一个盒子 → 6 个面都是潜在的 WALL Provider
     * - 选区是一个平面 → 边缘是潜在的 EDGE Provider
     * <p>
     * v1 骨架：返回空列表
     * v2 实现：基于几何分析自动生成 Socket
     */
    public static List<SocketPlacement> inferFromSelection(
            ServerWorld world,
            Box selection,
            ComponentSocket consumer
    ) {
        // v1 骨架：返回空列表
        return new ArrayList<>();
    }

    /**
     * 批量评分排序（供 AI 选择最优位置）。
     * <p>
     * 策略：
     * - 按 SocketMatcher.matchScore 排序（如果 provider 和 consumer 匹配）
     * - 按距离排序（离玩家/中心点越近越好）
     * - 按可见性排序（玩家能看见的优先，v1 暂不实现）
     */
    public static List<SocketPlacement> sortByScore(
            List<SocketPlacement> placements,
            ComponentSocket provider,
            ComponentSocket consumer,
            BlockPos referencePos
    ) {
        if (placements == null || placements.isEmpty()) return placements;

        List<SocketPlacement> sorted = new ArrayList<>(placements);
        sorted.sort((a, b) -> {
            // 1. 按 matchScore 排序（降序）
            // v1：使用 provider 和 consumer 的匹配分数
            double scoreA = 0.0;
            double scoreB = 0.0;
            
            if (provider != null && consumer != null) {
                // 简单的匹配分数：context 和 shape 匹配
                if (provider.context == consumer.context) {
                    scoreA += 1.0;
                }
                if (provider.shape == consumer.shape) {
                    scoreA += 1.0;
                }
                
                scoreB = scoreA; // 对于相同的 provider/consumer，分数相同
            }

            // 2. 按距离排序（升序）
            if (referencePos != null) {
                double distA = a.origin().getSquaredDistance(referencePos);
                double distB = b.origin().getSquaredDistance(referencePos);
                return Double.compare(distA, distB);
            }

            return 0;
        });

        return sorted;
    }
}
