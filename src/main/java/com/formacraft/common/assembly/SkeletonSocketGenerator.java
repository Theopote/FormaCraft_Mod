package com.formacraft.common.assembly;

import com.formacraft.common.component.socket.Socket;
import com.formacraft.common.component.socket.SocketProviders;
import com.formacraft.common.component.socket.SocketQueryContext;
import com.formacraft.common.component.socket.SocketQueryContextBuilder;
import com.formacraft.client.tool.OutlineTool;
import com.formacraft.client.tool.PathTool;
import com.formacraft.client.tool.SelectionTool;
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
 * <p>
 * 这个类将骨架转换为 Socket，供 AutoAssembler 使用。
 */
public final class SkeletonSocketGenerator {
    private SkeletonSocketGenerator() {}

    /**
     * 从骨架生成 Socket 列表
     * 
     * @param skeleton 骨架计划
     * @param world 服务器世界
     * @param origin 原点位置
     * @return Socket 列表
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

        // 根据骨架类型选择 SocketProfile
        SkeletonSocketProfile profile = getSocketProfile(skeleton.type);
        if (profile == null) {
            return sockets;
        }

        // 创建 SocketQueryContext（从工具状态或骨架几何）
        SocketQueryContext ctx = createContextFromSkeleton(skeleton, origin);

        // 使用 SocketProviders 收集 Socket
        sockets = SocketProviders.collect(world, ctx);

        return sockets;
    }

    /**
     * 根据骨架类型获取 SocketProfile
     */
    private static SkeletonSocketProfile getSocketProfile(SkeletonType type) {
        if (type == null) {
            return null;
        }

        // v1 简化：根据骨架类型返回默认配置
        return switch (type) {
            case PATH_POLYLINE -> SkeletonSocketProfile.forRoad();
            case VERTICAL_TAPER -> SkeletonSocketProfile.forTower();
            case PERIMETER_LOOP -> SkeletonSocketProfile.forCastleWall();
            default -> null;
        };
    }

    /**
     * 从骨架创建 SocketQueryContext
     */
    private static SocketQueryContext createContextFromSkeleton(
            ExecutableSkeletonPlan skeleton,
            BlockPos origin
    ) {
        SocketQueryContext ctx = new SocketQueryContext();
        ctx.focus = new Vec3d(origin.getX(), origin.getY(), origin.getZ());
        ctx.radius = 64;

        // v1 简化：尝试从工具状态获取（如果有）
        // 如果没有工具状态，使用骨架的几何信息（需要根据具体骨架类型实现）
        // 这里先使用工具状态作为示例
        // 注意：在服务器端，工具状态可能不可用，需要从骨架几何推导
        try {
            ctx = SocketQueryContextBuilder.fromTools(
                    SelectionTool.INSTANCE,
                    OutlineTool.INSTANCE,
                    PathTool.INSTANCE,
                    ctx.focus
            );
        } catch (Exception e) {
            // 如果工具状态不可用（服务器端），使用默认上下文
            // v1：后续可以从 skeleton 的几何信息推导 Socket
        }

        return ctx;
    }
}
