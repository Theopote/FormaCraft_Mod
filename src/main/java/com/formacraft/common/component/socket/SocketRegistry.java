package com.formacraft.common.component.socket;

import com.formacraft.common.buildcontext.BuildContext;

import java.util.ArrayList;
import java.util.List;

/**
 * SocketRegistry（插槽注册表）：管理所有 SocketProvider。
 * <p>
 * 核心思想：
 * - Socket 来自"建筑骨架 / Skeleton / Geometry"
 * - 多个 SocketProvider 可以同时工作
 * - 自动合并所有 Socket
 */
public final class SocketRegistry {
    private SocketRegistry() {}

    private static final List<SocketProvider> providers = new ArrayList<>();

    /**
     * 注册 SocketProvider
     */
    public static void register(SocketProvider provider) {
        if (provider != null) {
            providers.add(provider);
        }
    }

    /**
     * 获取所有 Socket
     * 
     * @param ctx 构建上下文
     * @return 所有 Socket 列表
     */
    public static List<Socket> getAllSockets(BuildContext ctx) {
        List<Socket> allSockets = new ArrayList<>();

        for (SocketProvider provider : providers) {
            List<Socket> sockets = provider.provideSockets(ctx);
            if (sockets != null) {
                allSockets.addAll(sockets);
            }
        }

        return allSockets;
    }

    /**
     * 初始化默认 SocketProvider
     */
    public static void initialize() {
        // 注册默认的 SocketProvider
        register(new WallSocketProvider());
        // 未来可以添加：RoofSocketProvider, EdgeSocketProvider, FloorSocketProvider 等
    }
}
