package com.formacraft.common.mass.derived;

import com.formacraft.common.component.socket.Socket;
import com.formacraft.common.component.socket.SocketType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * SkeletonToSocketDeriver（从 Skeleton 派生 Socket）
 * <p>
 * 🎯 核心职责：
 * 从派生出的 Skeleton 生成 Socket（装配点）
 * <p>
 * Skeleton = 面
 * Socket = 面上的"可放东西的位置 / 方向"
 * <p>
 * 派生规则：
 * - Exterior Skeleton → WINDOW_SOCKET, DOOR_SOCKET, BALCONY_SOCKET
 * - Interface Skeleton → DOOR_SOCKET, ARCH_SOCKET, OPENING_SOCKET
 * - Top Skeleton → ROOF_SOCKET, ROOF_EDGE_SOCKET
 */
public final class SkeletonToSocketDeriver {

    private SkeletonToSocketDeriver() {}

    /**
     * 从 Skeleton 列表派生所有 Socket
     *
     * @param skeletons Skeleton 列表
     * @return 派生出的 Socket 列表
     */
    public static List<Socket> deriveSockets(List<MassDerivedSkeleton> skeletons) {
        if (skeletons == null || skeletons.isEmpty()) {
            return List.of();
        }

        List<Socket> sockets = new ArrayList<>();

        for (MassDerivedSkeleton skeleton : skeletons) {
            sockets.addAll(deriveSocketsFromSkeleton(skeleton));
        }

        return sockets;
    }

    /**
     * 从单个 Skeleton 派生 Socket
     */
    private static List<Socket> deriveSocketsFromSkeleton(MassDerivedSkeleton skeleton) {
        List<Socket> sockets = new ArrayList<>();

        switch (skeleton.context) {
            case EXTERIOR -> {
                // Exterior Skeleton → 墙面 Socket
                if (skeleton.kind == MassDerivedSkeleton.SkeletonKind.WALL ||
                    skeleton.kind == MassDerivedSkeleton.SkeletonKind.FACADE) {
                    sockets.addAll(deriveExteriorWallSockets(skeleton));
                } else if (skeleton.kind == MassDerivedSkeleton.SkeletonKind.ROOF ||
                          skeleton.kind == MassDerivedSkeleton.SkeletonKind.TERRACE) {
                    sockets.addAll(deriveTopSockets(skeleton));
                }
            }
            case INTERIOR, CONNECTION -> {
                // Interface Skeleton → 内部 Socket
                if (skeleton.kind == MassDerivedSkeleton.SkeletonKind.WALL ||
                    skeleton.kind == MassDerivedSkeleton.SkeletonKind.FLOOR ||
                    skeleton.kind == MassDerivedSkeleton.SkeletonKind.CEILING) {
                    sockets.addAll(deriveInterfaceSockets(skeleton));
                }
            }
        }

        return sockets;
    }

    /**
     * 派生外立面 Socket（墙面）
     * <p>
     * 派生：
     * - WINDOW_SOCKET（窗户）
     * - DOOR_SOCKET（门）
     * - BALCONY_SOCKET（如果外侧是悬空）
     */
    private static List<Socket> deriveExteriorWallSockets(MassDerivedSkeleton skeleton) {
        List<Socket> sockets = new ArrayList<>();

        // v1 简化：在每个 Skeleton 位置上生成一个通用的墙面 Socket
        // 未来：需要根据高度区间、位置条件等筛选合适的 Socket 位置

        for (BlockPos pos : skeleton.positions) {
            // 检查是否在合适的高度范围内（不在角落等）
            if (isValidWallSocketPosition(pos, skeleton)) {
                // 创建墙面 Socket
                Box bounds = createSocketBounds(pos, skeleton.facing);

                Socket socket = new Socket(
                        SocketType.WALL_SURFACE,
                        bounds,
                        skeleton.facing,
                        new Socket.SemanticContext(true, "wall")
                );

                sockets.add(socket);

                // 在合适的位置创建 WALL_OPENING Socket（门/窗）
                if (shouldCreateOpeningSocket(pos, skeleton)) {
                    Socket openingSocket = new Socket(
                            SocketType.WALL_OPENING,
                            bounds,
                            skeleton.facing,
                            new Socket.SemanticContext(true, "wall_opening")
                    );
                    sockets.add(openingSocket);
                }
            }
        }

        return sockets;
    }

    /**
     * 派生内部 Socket（接口）
     * <p>
     * 派生：
     * - DOOR_SOCKET（门）
     * - ARCH_SOCKET（拱门）
     * - OPENING_SOCKET（开口）
     */
    private static List<Socket> deriveInterfaceSockets(MassDerivedSkeleton skeleton) {
        List<Socket> sockets = new ArrayList<>();

        // v1 简化：在 Interface Skeleton 上生成门洞 Socket
        for (BlockPos pos : skeleton.positions) {
            if (isValidInterfaceSocketPosition(pos, skeleton)) {
                Box bounds = createSocketBounds(pos, skeleton.facing);

                Socket socket = new Socket(
                        SocketType.WALL_OPENING,
                        bounds,
                        skeleton.facing,
                        new Socket.SemanticContext(false, "interior_opening")
                );

                sockets.add(socket);
            }
        }

        return sockets;
    }

    /**
     * 派生顶部 Socket（屋顶/平台）
     * <p>
     * 派生：
     * - ROOF_SOCKET（屋顶表面）
     * - ROOF_EDGE_SOCKET（如果相邻外暴露）
     */
    private static List<Socket> deriveTopSockets(MassDerivedSkeleton skeleton) {
        List<Socket> sockets = new ArrayList<>();

        // v1 简化：在 Top Skeleton 上生成屋顶 Socket
        for (BlockPos pos : skeleton.positions) {
            Box bounds = createSocketBounds(pos, Direction.UP);

            Socket socket = new Socket(
                    SocketType.ROOF_SLOPE, // v1 简化：使用 ROOF_SLOPE
                    bounds,
                    Direction.UP,
                    new Socket.SemanticContext(true, "roof")
            );

            sockets.add(socket);
        }

        return sockets;
    }

    /**
     * 检查是否是有效的墙面 Socket 位置
     * <p>
     * v1 简化：不在角落、在合适的高度范围内
     */
    private static boolean isValidWallSocketPosition(BlockPos pos, MassDerivedSkeleton skeleton) {
        // v1 简化：只检查高度是否在范围内
        return pos.getY() >= skeleton.minY && pos.getY() <= skeleton.maxY;
    }

    /**
     * 检查是否应该创建开口 Socket
     * <p>
     * v1 简化：每隔一定距离创建一个
     */
    private static boolean shouldCreateOpeningSocket(BlockPos pos, MassDerivedSkeleton skeleton) {
        // v1 简化：每隔 3 个方块创建一个开口 Socket
        int spacing = 3;
        return (pos.getX() + pos.getY() + pos.getZ()) % spacing == 0;
    }

    /**
     * 检查是否是有效的内部 Socket 位置
     */
    private static boolean isValidInterfaceSocketPosition(BlockPos pos, MassDerivedSkeleton skeleton) {
        // v1 简化：与墙面 Socket 相同的逻辑
        return isValidWallSocketPosition(pos, skeleton);
    }

    /**
     * 创建 Socket 的边界框
     */
    private static Box createSocketBounds(BlockPos pos, Direction facing) {
        // v1 简化：使用单个方块的边界
        return new Box(
                pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1
        );
    }
}
