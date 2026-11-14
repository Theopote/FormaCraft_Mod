package com.formacraft.common.component.socket;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/** Scores and sorts socket placements supplied by callers. */
public final class SocketFinder {
    private SocketFinder() {}

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
