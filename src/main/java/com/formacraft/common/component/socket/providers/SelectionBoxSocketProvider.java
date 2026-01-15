package com.formacraft.common.component.socket.providers;

import com.formacraft.common.component.socket.Socket;
import com.formacraft.common.component.socket.SocketQueryContext;
import com.formacraft.common.component.socket.SocketType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * SelectionBoxSocketProvider（选区 → 墙面/边缘/地面 sockets）。
 * <p>
 * 产生：
 * - FLOOR_SURFACE：底面一块（用于柱基、地面装饰）
 * - WALL_SURFACE：四个侧面（用于门窗装饰、壁龛、雨棚等）
 * - EDGE_OUTER：顶面外圈（用于栏杆、女儿墙）
 */
public final class SelectionBoxSocketProvider implements ToolBasedSocketProvider {

    @Override
    public List<Socket> provide(World world, SocketQueryContext ctx) {
        List<Socket> out = new ArrayList<>();
        if (ctx.selectionMin == null || ctx.selectionMax == null) return out;

        BlockPos min = new BlockPos(
                Math.min(ctx.selectionMin.getX(), ctx.selectionMax.getX()),
                Math.min(ctx.selectionMin.getY(), ctx.selectionMax.getY()),
                Math.min(ctx.selectionMin.getZ(), ctx.selectionMax.getZ())
        );
        BlockPos max = new BlockPos(
                Math.max(ctx.selectionMin.getX(), ctx.selectionMax.getX()),
                Math.max(ctx.selectionMin.getY(), ctx.selectionMax.getY()),
                Math.max(ctx.selectionMin.getZ(), ctx.selectionMax.getZ())
        );

        int x0 = min.getX(), y0 = min.getY(), z0 = min.getZ();
        int x1 = max.getX() + 1, y1 = max.getY() + 1, z1 = max.getZ() + 1;

        // FLOOR_SURFACE：底面（y0）
        out.add(new Socket(
                SocketType.FLOOR_SURFACE,
                new Box(x0, y0, z0, x1, y0 + 0.1, z1),
                Direction.UP,
                null
        ));

        // WALL_SURFACE：四面
        out.add(new Socket(SocketType.WALL_SURFACE, new Box(x0, y0, z0, x0 + 0.1, y1, z1), Direction.WEST, null));  // west face
        out.add(new Socket(SocketType.WALL_SURFACE, new Box(x1 - 0.1, y0, z0, x1, y1, z1), Direction.EAST, null));  // east face
        out.add(new Socket(SocketType.WALL_SURFACE, new Box(x0, y0, z0, x1, y1, z0 + 0.1), Direction.NORTH, null)); // north face
        out.add(new Socket(SocketType.WALL_SURFACE, new Box(x0, y0, z1 - 0.1, x1, y1, z1), Direction.SOUTH, null)); // south face

        // EDGE_OUTER：顶圈（y1）
        // v1：直接给一圈 thin box（后续可细分为段）
        out.add(new Socket(
                SocketType.EDGE_OUTER,
                new Box(x0, y1 - 0.1, z0, x1, y1 + 0.1, z1),
                Direction.UP,
                null
        ));

        return out;
    }
}
