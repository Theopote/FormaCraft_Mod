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

    /** Horizontal fill stripe in XZ (y chosen by the renderer). */
    record FillStripe(double x1, double x2, double z) {}

    /** Adaptive Z step so large footprints stay near ~64 stripes. */
    static int fillStep(int minZ, int maxZ) {
        return Math.max(1, (maxZ - minZ) / 64);
    }

    /** Precompute polygon scanline fill for committed overlays (avoids per-frame GC). */
    static List<FillStripe> polygonFillStripes(List<BlockPos> poly) {
        if (poly == null || poly.size() < 3) return List.of();
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : poly) {
            minZ = Math.min(minZ, p.getZ());
            maxZ = Math.max(maxZ, p.getZ());
        }
        if (minZ > maxZ) return List.of();
        int step = fillStep(minZ, maxZ);
        List<FillStripe> out = new ArrayList<>();
        List<Double> xs = new ArrayList<>();
        for (int zz = minZ; zz <= maxZ; zz += step) {
            xs.clear();
            intersectScanlineInto(poly, zz + 0.5, xs);
            if (xs.size() < 2) continue;
            xs.sort(Double::compareTo);
            double z = zz + 0.5;
            for (int i = 0; i + 1 < xs.size(); i += 2) {
                out.add(new FillStripe(xs.get(i), xs.get(i + 1), z));
            }
        }
        return List.copyOf(out);
    }

    static List<FillStripe> circleFillStripes(BlockPos center, int radius) {
        if (center == null || radius <= 0) return List.of();
        double cx = center.getX() + 0.5;
        double cz = center.getZ() + 0.5;
        int step = Math.max(1, radius / 32);
        List<FillStripe> out = new ArrayList<>();
        for (int dz = -radius; dz <= radius; dz += step) {
            double dx = Math.sqrt(Math.max(0.0, (double) radius * radius - (double) dz * dz));
            out.add(new FillStripe(cx - dx, cx + dx, cz + dz));
        }
        return List.copyOf(out);
    }

    static void intersectScanlineInto(List<BlockPos> poly, double z, List<Double> xs) {
        int n = poly.size();
        for (int i = 0; i < n; i++) {
            BlockPos a = poly.get(i);
            BlockPos b = poly.get((i + 1) % n);
            double z1 = a.getZ() + 0.5;
            double z2 = b.getZ() + 0.5;
            double x1 = a.getX() + 0.5;
            double x2 = b.getX() + 0.5;
            if (z1 == z2) continue;
            if (!(z >= Math.min(z1, z2) && z < Math.max(z1, z2))) continue;
            double t = (z - z1) / (z2 - z1);
            xs.add(x1 + t * (x2 - x1));
        }
    }

    /**
     * Liang–Barsky clip of an XZ segment against an inclusive AABB.
     * @return {x1,z1,x2,z2} or null if fully outside
     */
    static double[] clipSegmentXZ(
            double x1, double z1, double x2, double z2,
            double minX, double maxX, double minZ, double maxZ
    ) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        double u0 = 0.0;
        double u1 = 1.0;
        double[] p = {-dx, dx, -dz, dz};
        double[] q = {x1 - minX, maxX - x1, z1 - minZ, maxZ - z1};
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0.0) {
                if (q[i] < 0.0) return null;
                continue;
            }
            double r = q[i] / p[i];
            if (p[i] < 0.0) {
                if (r > u1) return null;
                if (r > u0) u0 = r;
            } else {
                if (r < u0) return null;
                if (r < u1) u1 = r;
            }
        }
        return new double[]{
                x1 + u0 * dx, z1 + u0 * dz,
                x1 + u1 * dx, z1 + u1 * dz
        };
    }
}
