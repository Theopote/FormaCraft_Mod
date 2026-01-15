package com.formacraft.common.component.socket;

import com.formacraft.common.buildcontext.BuildContext;

import java.util.List;

/**
 * SocketProvider（插槽提供者）：谁来"生成 socket"。
 * <p>
 * 核心思想：
 * - Socket 不是构件定义的
 * - Socket 来自"建筑骨架 / Skeleton / Geometry"
 * - AI 永远不碰 Socket
 * - Socket 是世界几何 + 建筑语义自动推导的
 */
public interface SocketProvider {
    /**
     * 提供 Socket 列表
     * 
     * @param ctx 构建上下文
     * @return Socket 列表
     */
    List<Socket> provideSockets(BuildContext ctx);
}
