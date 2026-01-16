package com.formacraft.common.component.socket.continuous;

import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * FacingResolver（朝向解析器）：从段和连续插槽计算朝向。
 */
public final class FacingResolver {
    private FacingResolver() {}

    /**
     * 从段计算朝向
     * 
     * @param segment 段
     * @param socket 连续插槽（用于获取法线）
     * @return 朝向（Direction）
     */
    public static Direction fromSegment(Segment segment, ContinuousSocket socket) {
        if (segment == null || segment.direction() == null) {
            return Direction.SOUTH;
        }

        Vec3d dir = segment.direction();
        
        // 将 Vec3d 方向转换为 Direction（水平方向）
        double ax = Math.abs(dir.x);
        double az = Math.abs(dir.z);
        
        if (ax >= az) {
            return dir.x >= 0 ? Direction.EAST : Direction.WEST;
        } else {
            return dir.z >= 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }
}
