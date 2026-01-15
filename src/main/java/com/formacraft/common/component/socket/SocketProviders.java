package com.formacraft.common.component.socket;

import com.formacraft.common.component.socket.providers.*;

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
}
