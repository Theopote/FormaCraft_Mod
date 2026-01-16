package com.formacraft.common.component.socket;

import com.formacraft.common.buildcontext.BuildContext;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * WallSocketProvider（墙体插槽提供者）：示例实现。
 * <p>
 * 从建筑骨架 / Skeleton / Geometry 中提取墙体的 Socket。
 */
public final class WallSocketProvider implements SocketProvider {
    public WallSocketProvider() {}

    @Override
    public List<Socket> provideSockets(BuildContext ctx) {

        // TODO: 从 BuildContext 中获取墙体信息
        // 这里需要根据实际的建筑骨架 / Skeleton / Geometry 来实现
        // 示例实现：

        // 假设从 BuildContext 中获取墙体段
        // List<WallSegment> walls = ctx.getWalls();
        // for (WallSegment wall : walls) {
        //     // 墙面 Socket
        //     sockets.add(new Socket(
        //         SocketType.WALL_SURFACE,
        //         wall.getSurfaceBox(),
        //         wall.getNormal(),
        //         new Socket.SemanticContext(wall.isExterior(), wall.getSemanticTag())
        //     ));
        //
        //     // 墙洞 Socket（如果有）
        //     if (wall.hasOpening()) {
        //         sockets.add(new Socket(
        //             SocketType.WALL_OPENING,
        //             wall.getOpeningBox(),
        //             wall.getNormal(),
        //             new Socket.SemanticContext(wall.isExterior(), wall.getSemanticTag())
        //         ));
        //     }
        // }

        return new ArrayList<>();
    }

    /**
     * 墙体段（示例数据结构，实际需要根据 BuildContext 实现）
     */
    public static class WallSegment {
        public Box getSurfaceBox() {
            // TODO: 实现
            return null;
        }

        public Direction getNormal() {
            // TODO: 实现
            return Direction.NORTH;
        }

        public Socket.SemanticContext getSemanticContext() {
            // TODO: 实现
            return new Socket.SemanticContext(false, "wall");
        }

        public boolean hasOpening() {
            // TODO: 实现
            return false;
        }

        public Box getOpeningBox() {
            // TODO: 实现
            return null;
        }
    }
}
