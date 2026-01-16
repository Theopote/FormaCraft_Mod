package com.formacraft.common.component.socket;

import com.formacraft.common.component.socket.providers.*;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * SocketProviders（Socket 提供者聚合器）：注册与聚合。
 * <p>
 * 你以后新增 provider，只要 register()。
 */
public final class SocketProviders {

    private static final List<ToolBasedSocketProvider> PROVIDERS = new ArrayList<>();

    static {
        // v1 默认注册
        PROVIDERS.add(new SelectionBoxSocketProvider());
        PROVIDERS.add(new OutlinePolygonSocketProvider());
        PROVIDERS.add(new PathPolylineSocketProvider());
        PROVIDERS.add(new WallOpeningSocketProvider()); // openings 通常依赖 wall surface 的 bounds
    }

    private SocketProviders() {}

    /**
     * 注册 SocketProvider
     */
    public static void register(ToolBasedSocketProvider provider) {
        if (provider != null) {
            PROVIDERS.add(provider);
        }
    }

    /**
     * 收集所有 Socket
     * 
     * @param world 世界
     * @param ctx 查询上下文
     * @return Socket 列表
     */
    public static List<Socket> collect(World world, SocketQueryContext ctx) {
        List<Socket> all = new ArrayList<>();
        for (ToolBasedSocketProvider p : PROVIDERS) {
            List<Socket> sockets = p.provide(world, ctx);
            if (sockets != null) {
                all.addAll(sockets);
            }
        }
        // v1：不做复杂去重，只做简单裁剪（距离 focus 太远的丢掉）
        if (ctx.focus != null && ctx.radius > 0) {
            double maxDistSq = (double) ctx.radius * ctx.radius;
            all.removeIf(s -> s.center().squaredDistanceTo(ctx.focus) > maxDistSq);
        }
        return all;
    }

    /**
     * 收集并排序 Socket（使用 SocketFinder.sortByScore）
     * 
     * @param world 世界
     * @param ctx 查询上下文
     * @param consumer 目标 Consumer Socket（用于匹配过滤，可选）
     * @param referencePos 参考位置（用于距离排序）
     * @return 排序后的 Socket 列表
     */
    public static List<Socket> collectAndSort(
            World world,
            SocketQueryContext ctx,
            ComponentSocket consumer,
            BlockPos referencePos
    ) {
        // 1. 收集所有 Socket
        List<Socket> sockets = collect(world, ctx);
        if (sockets.isEmpty()) {
            return sockets;
        }

        // 2. 转换为 SocketPlacement（使用适配器）
        List<SocketPlacement> placements = SocketToPlacementAdapter.toPlacements(sockets);

        // 3. 如果有 consumer，提取 provider 信息用于排序
        ComponentSocket provider = null;
        if (consumer != null && !sockets.isEmpty()) {
            // v1 简化：从第一个 Socket 推断 provider（实际应该从 Socket 的 ComponentSocket 获取）
            // 这里暂时使用 null，排序主要按距离
        }

        // 4. 使用 SocketFinder 排序
        List<SocketPlacement> sortedPlacements = SocketFinder.sortByScore(
                placements, null, consumer, referencePos
        );

        // 5. 转换回 Socket（保持顺序）
        List<Socket> sortedSockets = new ArrayList<>();
        for (SocketPlacement placement : sortedPlacements) {
            // 找到对应的原始 Socket（通过 id 匹配）
            for (Socket socket : sockets) {
                if (socket.id != null && socket.id.equals(placement.socketId())) {
                    sortedSockets.add(socket);
                    break;
                }
            }
        }

        return sortedSockets;
    }

    /**
     * 从已放置的组件实例中查找 Provider Socket（使用 SocketFinder）
     * 
     * @param world 服务器世界
     * @param searchBox 搜索范围
     * @param consumer 目标 Consumer Socket
     * @return Socket 列表（从 SocketPlacement 转换而来）
     */
    public static List<Socket> findFromInstances(
            ServerWorld world,
            Box searchBox,
            ComponentSocket consumer
    ) {
        // 1. 使用 SocketFinder 查找 Provider Socket
        List<SocketPlacement> placements = SocketFinder.findProviders(world, searchBox, consumer);

        // 2. 转换为 Socket
        List<Socket> sockets = new ArrayList<>();
        for (SocketPlacement placement : placements) {
            // 从 SocketPlacement 推断 SocketType（简化处理）
            SocketType socketType = SocketType.WALL_SURFACE; // v1 默认
            Socket socket = SocketToPlacementAdapter.toSocket(placement, socketType);
            if (socket != null) {
                sockets.add(socket);
            }
        }

        return sockets;
    }
}
