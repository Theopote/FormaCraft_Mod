package com.formacraft.common.component.socket.providers;

import com.formacraft.common.component.socket.Socket;
import com.formacraft.common.component.socket.SocketProvider;
import com.formacraft.common.component.socket.SocketQueryContext;
import net.minecraft.world.World;

import java.util.List;

/**
 * ToolBasedSocketProvider（基于工具的 SocketProvider）。
 * <p>
 * 这是新的接口，使用 World + SocketQueryContext，而不是 BuildContext。
 * <p>
 * 用于从 SelectionTool / OutlineTool / PathTool 等工具输入生成 sockets。
 */
public interface ToolBasedSocketProvider extends SocketProvider {
    /**
     * 提供 Socket 列表
     * 
     * @param world 世界
     * @param ctx 查询上下文（工具/骨架输入）
     * @return Socket 列表
     */
    List<Socket> provide(World world, SocketQueryContext ctx);

    /**
     * 默认实现：适配旧接口（如果需要）
     */
    @Override
    default List<Socket> provideSockets(com.formacraft.common.buildcontext.BuildContext ctx) {
        // v1：旧接口暂不支持，返回空列表
        // 未来可以适配 BuildContext 到 SocketQueryContext
        return List.of();
    }
}
