package com.formacraft.common.component.socket;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3i;

/**
 * SocketPlacement（Socket 定位结果）v1：匹配成功后的输出。
 * <p>
 * 职责：
 * - 告诉 Variant 编译器/Generator："在哪里、朝向哪里、多大尺寸"
 * - 作为 PlacementContext 的输入（工厂方法：fromSocket）
 * <p>
 * 字段：
 * - origin：世界坐标（组件锚点应放置的位置）
 * - facing：朝向（可能为 null，表示无朝向）
 * - size：尺寸（可能为 null，表示无尺寸约束）
 * - socketId：匹配的 socket id（用于调试/追踪）
 */
public record SocketPlacement(
        BlockPos origin,
        Direction facing,
        Vec3i size,
        String socketId
) {
    /**
     * 创建一个简单的 placement（只有位置，无朝向/尺寸）。
     */
    public static SocketPlacement simple(BlockPos origin, String socketId) {
        return new SocketPlacement(origin, null, null, socketId);
    }

    /**
     * 创建一个带朝向的 placement（例如：门、窗）。
     */
    public static SocketPlacement withFacing(BlockPos origin, Direction facing, String socketId) {
        return new SocketPlacement(origin, facing, null, socketId);
    }

    /**
     * 创建一个带尺寸的 placement（例如：矩形洞口）。
     */
    public static SocketPlacement withSize(BlockPos origin, Direction facing, Vec3i size, String socketId) {
        return new SocketPlacement(origin, facing, size, socketId);
    }

    /**
     * 转换为字符串（用于调试）。
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("SocketPlacement[");
        sb.append("origin=").append(origin);
        if (facing != null) sb.append(", facing=").append(facing);
        if (size != null) sb.append(", size=").append(size.getX()).append("×")
                .append(size.getY()).append("×").append(size.getZ());
        if (socketId != null) sb.append(", socket=").append(socketId);
        sb.append("]");
        return sb.toString();
    }
}
