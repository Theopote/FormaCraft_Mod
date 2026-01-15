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
 * OutlinePolygonSocketProvider（轮廓 → 边缘 sockets）。
 * <p>
 * v1 策略：
 * - outline polygon（XZ）→ 边界 polyline
 * - 沿边采样点（step=2）
 * - 每个采样点生成一个 EDGE_OUTER 小 socket（1×1×0.2）
 * <p>
 * 这会让栏杆/女儿墙/外边缘装饰"沿轮廓走"。
 */
public final class OutlinePolygonSocketProvider implements ToolBasedSocketProvider {

    @Override
    public List<Socket> provide(World world, SocketQueryContext ctx) {
        List<Socket> out = new ArrayList<>();
        if (ctx.outlinePolygon == null || ctx.outlinePolygon.size() < 3) return out;

        // 封闭 polygon
        List<Vec3d> poly = new ArrayList<>(ctx.outlinePolygon);
        if (!poly.get(0).equals(poly.get(poly.size() - 1))) {
            poly.add(poly.get(0));
        }

        // 采样边界
        List<Vec3d> samples = SocketUtil.samplePolyline(poly, 2.0);

        for (int i = 0; i < samples.size() - 1; i++) {
            Vec3d p = samples.get(i);
            Vec3d p2 = samples.get(i + 1);
            Vec3d dir = p2.subtract(p);
            Direction tangent = SocketUtil.approxHorizontalDir(dir);

            BlockPos bp = BlockPos.ofFloored(p.x, p.y, p.z);
            Box b = new Box(bp.getX(), bp.getY(), bp.getZ(), bp.getX() + 1, bp.getY() + 0.2, bp.getZ() + 1);

            out.add(new Socket(SocketType.EDGE_OUTER, b, Direction.UP, tangent));
        }

        return out;
    }
}
