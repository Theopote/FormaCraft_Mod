package com.formacraft.client.tool;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import java.util.List;
import java.util.ArrayList;

/** Geometry shared by tool interaction and its regression tests. */
final class ToolGeometry {
    private ToolGeometry() {}

    /** Both corners are inclusive. Clamp at the opposite face instead of flipping handles. */
    static BlockPos[] resize(BlockPos min, BlockPos max, Direction face, int delta) {
        BlockPos lo = min, hi = max;
        switch (face) {
            case WEST -> lo = new BlockPos(Math.min(min.getX() + delta, max.getX()), min.getY(), min.getZ());
            case EAST -> hi = new BlockPos(Math.max(max.getX() + delta, min.getX()), max.getY(), max.getZ());
            case DOWN -> lo = new BlockPos(min.getX(), Math.min(min.getY() + delta, max.getY()), min.getZ());
            case UP -> hi = new BlockPos(max.getX(), Math.max(max.getY() + delta, min.getY()), max.getZ());
            case NORTH -> lo = new BlockPos(min.getX(), min.getY(), Math.min(min.getZ() + delta, max.getZ()));
            case SOUTH -> hi = new BlockPos(max.getX(), max.getY(), Math.max(max.getZ() + delta, min.getZ()));
        }
        return new BlockPos[]{lo, hi};
    }

    /** Closest point on the handle axis to the cursor ray; independent of terrain hits. */
    static Vec3d axisPoint(Vec3d camera, Vec3d ray, Vec3d origin, Direction.Axis axis) {
        Vec3d unit = switch (axis) {
            case X -> new Vec3d(1, 0, 0);
            case Y -> new Vec3d(0, 1, 0);
            case Z -> new Vec3d(0, 0, 1);
        };
        Vec3d direction = ray.normalize();
        double dot = unit.dotProduct(direction);
        double denominator = 1 - dot * dot;
        if (denominator < 1e-6) return null;
        Vec3d offset = camera.subtract(origin);
        double along = (unit.dotProduct(offset) - dot * direction.dotProduct(offset)) / denominator;
        double rayDistance = dot * along - direction.dotProduct(offset);
        if (rayDistance < 0) return null;
        return origin.add(unit.multiply(along));
    }

    /** Reject degenerate and self-intersecting XZ footprints before syncing them. */
    static List<BlockPos> normalizePolygon(List<BlockPos> points) {
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos p : points) {
            if (result.isEmpty() || !sameXZ(result.getLast(), p)) result.add(p);
        }
        if (result.size() > 1 && sameXZ(result.getFirst(), result.getLast())) result.removeLast();
        return result;
    }

    static boolean validPolygon(List<BlockPos> points) {
        int n = points.size();
        if (n < 3) return false;
        double area = 0;
        for (int i = 0; i < n; i++) {
            BlockPos a = points.get(i), b = points.get((i + 1) % n);
            if (sameXZ(a, b)) return false;
            area += (double) a.getX() * b.getZ() - (double) b.getX() * a.getZ();
            for (int j = i + 1; j < n; j++) {
                if (j == i + 1 || (i == 0 && j == n - 1)) continue;
                if (intersects(a, b, points.get(j), points.get((j + 1) % n))) return false;
            }
        }
        return area != 0;
    }

    static boolean sameXZ(BlockPos a, BlockPos b) {
        return a.getX() == b.getX() && a.getZ() == b.getZ();
    }

    private static double cross(BlockPos a, BlockPos b, BlockPos c) {
        return ((double) b.getX() - a.getX()) * ((double) c.getZ() - a.getZ())
                - ((double) b.getZ() - a.getZ()) * ((double) c.getX() - a.getX());
    }

    private static boolean onSegment(BlockPos a, BlockPos b, BlockPos p) {
        return cross(a, b, p) == 0 && p.getX() >= Math.min(a.getX(), b.getX())
                && p.getX() <= Math.max(a.getX(), b.getX()) && p.getZ() >= Math.min(a.getZ(), b.getZ())
                && p.getZ() <= Math.max(a.getZ(), b.getZ());
    }

    private static boolean intersects(BlockPos a, BlockPos b, BlockPos c, BlockPos d) {
        double abC = cross(a, b, c), abD = cross(a, b, d);
        double cdA = cross(c, d, a), cdB = cross(c, d, b);
        return (abC * abD < 0 && cdA * cdB < 0) || onSegment(a, b, c)
                || onSegment(a, b, d) || onSegment(c, d, a) || onSegment(c, d, b);
    }
}
