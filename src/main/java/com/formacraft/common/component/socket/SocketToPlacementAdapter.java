package com.formacraft.common.component.socket;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3i;

import java.util.ArrayList;
import java.util.List;

/**
 * SocketToPlacementAdapter（Socket 到 Placement 适配器）：将 Socket 转换为 SocketPlacement。
 * <p>
 * 用途：
 * - 将 Socket 转换为 SocketPlacement，以便使用 SocketFinder.sortByScore() 进行排序
 * - 桥接 Socket 和 SocketPlacement 两种数据格式
 */
public final class SocketToPlacementAdapter {
    private SocketToPlacementAdapter() {}

    /**
     * 将 Socket 转换为 SocketPlacement
     * 
     * @param socket Socket
     * @return SocketPlacement
     */
    public static SocketPlacement toPlacement(Socket socket) {
        if (socket == null) {
            return null;
        }

        // 从 Socket 的中心点获取原点
        BlockPos origin = socket.centerBlockPos();
        if (origin == null && socket.bounds != null) {
            origin = BlockPos.ofFloored(
                    socket.bounds.getCenter().x,
                    socket.bounds.getCenter().y,
                    socket.bounds.getCenter().z
            );
        }
        if (origin == null) {
            origin = BlockPos.ORIGIN;
        }

        // 从 Socket 获取朝向（normal 可以作为朝向）
        Direction facing = socket.normal;

        // 从 Socket 的 bounds 计算尺寸
        Vec3i size = null;
        if (socket.bounds != null) {
            double dx = socket.bounds.maxX - socket.bounds.minX;
            double dy = socket.bounds.maxY - socket.bounds.minY;
            double dz = socket.bounds.maxZ - socket.bounds.minZ;
            if (dx > 0 && dy > 0 && dz > 0) {
                size = new Vec3i((int) Math.ceil(dx), (int) Math.ceil(dy), (int) Math.ceil(dz));
            }
        }

        return new SocketPlacement(origin, facing, size, socket.id);
    }

    /**
     * 将 Socket 列表转换为 SocketPlacement 列表
     * 
     * @param sockets Socket 列表
     * @return SocketPlacement 列表
     */
    public static List<SocketPlacement> toPlacements(List<Socket> sockets) {
        List<SocketPlacement> placements = new ArrayList<>();
        if (sockets == null) {
            return placements;
        }

        for (Socket socket : sockets) {
            SocketPlacement placement = toPlacement(socket);
            if (placement != null) {
                placements.add(placement);
            }
        }

        return placements;
    }

    /**
     * 将 SocketPlacement 转换回 Socket（反向转换，用于兼容）
     * 
     * @param placement SocketPlacement
     * @param socketType Socket 类型
     * @return Socket（简化版本）
     */
    public static Socket toSocket(SocketPlacement placement, SocketType socketType) {
        if (placement == null || socketType == null) {
            return null;
        }

        // 创建简化的 Socket（仅包含必要信息）
        BlockPos origin = placement.origin();
        net.minecraft.util.math.Box bounds = null;
        if (placement.size() != null) {
            bounds = new net.minecraft.util.math.Box(
                    origin.getX(), origin.getY(), origin.getZ(),
                    origin.getX() + placement.size().getX(),
                    origin.getY() + placement.size().getY(),
                    origin.getZ() + placement.size().getZ()
            );
        } else {
            bounds = new net.minecraft.util.math.Box(
                    origin.getX(), origin.getY(), origin.getZ(),
                    origin.getX() + 1, origin.getY() + 1, origin.getZ() + 1
            );
        }

        return new Socket(socketType, bounds, placement.facing(), (Socket.SemanticContext) null);
    }
}
