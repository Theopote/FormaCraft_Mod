package com.formacraft.common.component.socket.providers;

import com.formacraft.common.component.socket.Socket;
import com.formacraft.common.component.socket.SocketQueryContext;
import com.formacraft.common.component.socket.SocketType;
import com.formacraft.common.component.socket.SocketUtil;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * PathPolylineSocketProvider（路径 → 路边 sockets）。
 * <p>
 * v1 策略：
 * - path polyline 采样（step=2）
 * - 每个采样点：
 *   - FLOOR_SURFACE（道路中心）
 *   - 两侧偏移 1 格的 EDGE_OUTER（路边，用于路灯/护栏/长城沿路）
 * <p>
 * 这里用"近似水平法线"实现左右偏移。
 */
public final class PathPolylineSocketProvider implements ToolBasedSocketProvider {

    @Override
    public List<Socket> provide(World world, SocketQueryContext ctx) {
        List<Socket> out = new ArrayList<>();
        if (ctx.paths == null || ctx.paths.isEmpty()) return out;

        for (List<Vec3d> path : ctx.paths) {
            if (path == null || path.size() < 2) continue;

            List<Vec3d> samples = SocketUtil.samplePolyline(path, 2.0);

            for (int i = 0; i < samples.size() - 1; i++) {
                Vec3d p = samples.get(i);
                Vec3d p2 = samples.get(i + 1);
                Vec3d d = p2.subtract(p);
                Direction tangent = SocketUtil.approxHorizontalDir(d);

                // 左右方向：tangent 的左/右（近似）
                Direction left = switch (tangent) {
                    case NORTH -> Direction.WEST;
                    case SOUTH -> Direction.EAST;
                    case WEST -> Direction.SOUTH;
                    case EAST -> Direction.NORTH;
                    default -> Direction.WEST;
                };
                Direction right = left.getOpposite();

                BlockPos center = BlockPos.ofFloored(p.x, p.y, p.z);

                // 路面 socket
                out.add(new Socket(
                        SocketType.FLOOR_SURFACE,
                        new Box(center.getX(), center.getY(), center.getZ(),
                                center.getX() + 1, center.getY() + 0.1, center.getZ() + 1),
                        Direction.UP,
                        tangent
                ));

                // 左边缘
                BlockPos lp = center.offset(left, 1);
                out.add(new Socket(
                        SocketType.EDGE_OUTER,
                        new Box(lp.getX(), lp.getY(), lp.getZ(),
                                lp.getX() + 1, lp.getY() + 0.2, lp.getZ() + 1),
                        Direction.UP,
                        tangent
                ));

                // 右边缘
                BlockPos rp = center.offset(right, 1);
                out.add(new Socket(
                        SocketType.EDGE_OUTER,
                        new Box(rp.getX(), rp.getY(), rp.getZ(),
                                rp.getX() + 1, rp.getY() + 0.2, rp.getZ() + 1),
                        Direction.UP,
                        tangent
                ));
            }
        }

        return out;
    }
}
