package com.formacraft.common.component.socket.continuous;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * Segment（段）：连续路径的一个片段，用于放置单个构件。
 */
public record Segment(
        /** 段的起始点索引（在采样点列表中的索引） */
        int startIndex,
        /** 段的结束点索引（不包含） */
        int endIndex,
        /** 段的采样点列表 */
        List<BlockPos> points,
        /** 段的中心点（用于锚点） */
        BlockPos center,
        /** 段的朝向（切线方向） */
        Vec3d direction,
        /** 段的长度（方块单位，近似） */
        double length
) {
    public Segment {
        if (points == null) {
            points = List.of();
        }
        if (center == null && !points.isEmpty()) {
            // 计算中心点
            int mid = points.size() / 2;
            center = points.get(mid);
        }
    }

    /**
     * 检查是否是转角段
     */
    public boolean isCorner() {
        // v1 简化：如果方向变化超过阈值，认为是转角
        // 这个判断会在 Segmenter 中完成
        return false;
    }
}
