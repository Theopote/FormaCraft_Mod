package com.formacraft.client.tool.placement;

import com.formacraft.common.logging.FcaLog;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

/**
 * EdgeDetector（v1 简化实现）：
 * - 通过“相邻为空气”判断边缘/角落
 * - 用于栏杆/护栏等 EDGE 语义构件
 */
public final class EdgeDetector {
    private EdgeDetector() {}

    private static final FcaLog LOG = FcaLog.of("EdgeDetector");

    public static boolean isEdge(MinecraftClient client, BlockPos pos) {
        if (pos == null) return false;
        // 优先使用 OutlineFootprint（真实建筑边界）
        try {
            if (OutlineFootprintIndex.hasShape()) {
                return OutlineFootprintIndex.isNearEdge(pos);
            }
        } catch (Throwable t) {
            LOG.debug("footprint isNearEdge failed pos={}", pos, t);
        }
        // fallback：空气邻居（简化）
        if (client == null || client.world == null) return false;
        World w = client.world;
        int airCount = 0;
        for (Direction d : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            if (w.getBlockState(pos.offset(d)).isAir()) airCount++;
        }
        return airCount >= 1;
    }

    public static boolean isCorner(MinecraftClient client, BlockPos pos) {
        if (pos == null) return false;
        // 优先使用 OutlineFootprint（真实角点）
        try {
            if (OutlineFootprintIndex.hasShape()) {
                return OutlineFootprintIndex.isNearCorner(pos);
            }
        } catch (Throwable t) {
            LOG.debug("footprint isNearCorner failed pos={}", pos, t);
        }
        // fallback：空气邻居（简化）
        if (client == null || client.world == null) return false;
        World w = client.world;
        boolean n = w.getBlockState(pos.north()).isAir();
        boolean s = w.getBlockState(pos.south()).isAir();
        boolean e = w.getBlockState(pos.east()).isAir();
        boolean w0 = w.getBlockState(pos.west()).isAir();
        return (n && e) || (e && s) || (s && w0) || (w0 && n);
    }

    /**
     * 返回沿边缘的“切线方向”（用于 ALONG_EDGE）。
     * v1：如果发现 outward（朝空气）方向，则返回其一个垂直方向作为切线。
     */
    public static Direction getEdgeDirection(MinecraftClient client, BlockPos pos) {
        if (pos == null) return Direction.SOUTH;
        // 优先使用 OutlineFootprint：返回最近边的切线方向（稳定）
        try {
            if (OutlineFootprintIndex.hasShape()) {
                Direction d = OutlineFootprintIndex.edgeTangentDirection(pos);
                if (d != null && d.getAxis().isHorizontal()) return d;
            }
        } catch (Throwable t) {
            LOG.debug("footprint edgeTangentDirection failed pos={}", pos, t);
        }
        // fallback：空气邻居（简化）
        if (client == null || client.world == null) return Direction.SOUTH;
        World w = client.world;
        Direction outward = null;
        for (Direction d : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            if (w.getBlockState(pos.offset(d)).isAir()) { outward = d; break; }
        }
        if (outward == null) return Direction.SOUTH;
        return outward.rotateYClockwise();
    }
}
