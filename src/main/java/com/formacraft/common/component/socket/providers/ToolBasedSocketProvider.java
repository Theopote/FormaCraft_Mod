package com.formacraft.common.component.socket.providers;

import com.formacraft.common.component.socket.Socket;
import com.formacraft.common.component.socket.SocketQueryContext;
import net.minecraft.world.World;

import java.util.List;

/**
 * ToolBasedSocketProvider（基于工具的 Socket 提供者）。
 * <p>
 * 使用 World + SocketQueryContext 生成 sockets，
 * 输入来自 SelectionTool / OutlineTool / PathTool 等工具。
 * <p>
 * 收集入口是 {@link com.formacraft.common.component.socket.SocketProviders#collect}，
 * 而不是本接口自身——实现类只负责产出 Socket 列表。
 */
public interface ToolBasedSocketProvider {
    /**
     * 提供 Socket 列表
     *
     * @param world 世界
     * @param ctx 查询上下文（工具/骨架输入）
     * @return Socket 列表
     */
    List<Socket> provide(World world, SocketQueryContext ctx);
}
