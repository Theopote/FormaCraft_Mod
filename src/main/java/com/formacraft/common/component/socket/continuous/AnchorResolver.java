package com.formacraft.common.component.socket.continuous;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * AnchorResolver（锚点解析器）：为段计算锚点位置。
 */
public final class AnchorResolver {
    private AnchorResolver() {}

    /**
     * 解析段的锚点位置
     * 
     * @param segment 段
     * @param policy 放置策略
     * @return 锚点位置
     */
    public static BlockPos resolve(Segment segment, ContinuousPlacementPolicy policy) {
        if (segment == null || segment.points().isEmpty()) {
            return BlockPos.ORIGIN;
        }

        if (policy.alignToCenter()) {
            // 居中对齐：使用段的中心点
            return segment.center() != null ? segment.center() : segment.points().get(segment.points().size() / 2);
        } else {
            // 左对齐：使用段的起始点
            return segment.points().get(0);
        }
    }
}
