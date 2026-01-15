package com.formacraft.common.component.socket;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * SocketUtil（Socket 工具类）：一些几何工具。
 * <p>
 * 从 BlockPos 造 Box、沿边采样等。
 */
public final class SocketUtil {
    private SocketUtil() {}

    /** 以 blockpos 为单位盒（0..1） */
    public static Box blockBox(BlockPos p) {
        return new Box(p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 1, p.getZ() + 1);
    }

    /** 构造一个沿着墙面/边缘的薄盒（厚度 t） */
    public static Box thinBox(BlockPos a, BlockPos b, double thickness) {
        int minX = Math.min(a.getX(), b.getX());
        int minY = Math.min(a.getY(), b.getY());
        int minZ = Math.min(a.getZ(), b.getZ());
        int maxX = Math.max(a.getX(), b.getX()) + 1;
        int maxY = Math.max(a.getY(), b.getY()) + 1;
        int maxZ = Math.max(a.getZ(), b.getZ()) + 1;
        // thickness 这里只作为"视觉/命中"用途，v1 不强制沿法线压扁
        return new Box(minX, minY, minZ, maxX, maxY, maxZ).expand(thickness);
    }

    /** polyline 采样：按 step（方块）插值出点 */
    public static List<Vec3d> samplePolyline(List<Vec3d> pts, double step) {
        List<Vec3d> out = new ArrayList<>();
        if (pts == null || pts.size() < 2) return out;

        Vec3d prev = pts.get(0);
        out.add(prev);

        for (int i = 1; i < pts.size(); i++) {
            Vec3d cur = pts.get(i);
            Vec3d delta = cur.subtract(prev);
            double len = delta.length();
            if (len < 1e-6) continue;

            Vec3d dir = delta.multiply(1.0 / len);
            double t = step;
            while (t < len) {
                out.add(prev.add(dir.multiply(t)));
                t += step;
            }
            out.add(cur);
            prev = cur;
        }
        return out;
    }

    /** 从 2D 方向（XZ）推导一个近似 Direction（四向） */
    public static Direction approxHorizontalDir(Vec3d dir) {
        double ax = Math.abs(dir.x);
        double az = Math.abs(dir.z);
        if (ax >= az) return dir.x >= 0 ? Direction.EAST : Direction.WEST;
        return dir.z >= 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
