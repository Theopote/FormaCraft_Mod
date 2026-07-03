package com.formacraft.common.assembly;

import com.formacraft.common.component.socket.Socket;
import com.formacraft.common.component.socket.SocketProviders;
import com.formacraft.common.component.socket.SocketQueryContext;
import com.formacraft.common.skeleton.socket.SkeletonSocketProfile;
import com.formacraft.common.skeleton.SkeletonType;
import com.formacraft.common.skeleton.ExecutableSkeletonPlan;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * SkeletonSocketGenerator（骨架 Socket 生成器）：从骨架生成 Socket 列表。
 */
public final class SkeletonSocketGenerator {
    private SkeletonSocketGenerator() {}

    /**
     * 从骨架生成 Socket 列表。
     */
    public static List<Socket> generateSockets(
            ExecutableSkeletonPlan skeleton,
            ServerWorld world,
            BlockPos origin
    ) {
        List<Socket> sockets = new ArrayList<>();

        if (skeleton == null || world == null || origin == null) {
            return sockets;
        }

        SkeletonSocketProfile profile = getSocketProfile(skeleton.type);
        if (profile == null) {
            return sockets;
        }

        SocketQueryContext ctx = createContextFromSkeleton(skeleton, origin);
        return SocketProviders.collect(world, ctx);
    }

    private static SkeletonSocketProfile getSocketProfile(SkeletonType type) {
        if (type == null) {
            return null;
        }

        return switch (type) {
            case PATH_POLYLINE -> SkeletonSocketProfile.forRoad();
            case VERTICAL_TAPER -> SkeletonSocketProfile.forTower();
            case PERIMETER_LOOP -> SkeletonSocketProfile.forCastleWall();
            default -> null;
        };
    }

    private static SocketQueryContext createContextFromSkeleton(
            ExecutableSkeletonPlan skeleton,
            BlockPos origin
    ) {
        SocketQueryContext ctx = new SocketQueryContext();
        ctx.focus = new Vec3d(origin.getX(), origin.getY(), origin.getZ());
        ctx.radius = 64;
        ctx.includeOpenings = true;
        // v1：后续可从 skeleton 几何推导 outline/paths
        return ctx;
    }
}
