package com.formacraft.common.geometry.shape;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * M1 体素基元库：box / cylinder / cone / frustum / regular prism。
 * 输出相对 anchor 的整数体素坐标 (x,y,z)。
 */
public final class ShapeLibrary {

    public record Voxel(int x, int y, int z) {}

    private ShapeLibrary() {}

    public static List<Voxel> generate(ShapeSpec spec) {
        if (spec == null) {
            return List.of();
        }
        List<Voxel> out = new ArrayList<>();
        double cx = (spec.width() - 1) * 0.5;
        double cz = (spec.depth() - 1) * 0.5;
        double rotRad = Math.toRadians(spec.rotationYDeg());

        BiPredicate<Integer, Integer> inside = (x, z) -> contains(spec, x, z, cx, cz, rotRad);

        for (int y = 0; y < spec.height(); y++) {
            for (int x = 0; x < spec.width(); x++) {
                for (int z = 0; z < spec.depth(); z++) {
                    if (!inside.test(x, z)) {
                        continue;
                    }
                    if (!insideVertical(spec, x, y, z, cx, cz, rotRad)) {
                        continue;
                    }
                    if (spec.hollow() && isInteriorShell(spec, x, y, z, cx, cz, rotRad)) {
                        continue;
                    }
                    out.add(new Voxel(x, y, z));
                }
            }
        }
        return out;
    }

    private static boolean contains(ShapeSpec spec, int x, int z, double cx, double cz, double rotRad) {
        double[] rz = rotateY(x, z, cx, cz, -rotRad);
        double lx = rz[0];
        double lz = rz[1];
        return switch (spec.kind()) {
            case BOX -> true;
            case CYLINDER -> insideCircle(lx, lz, cx, cz, spec.radius());
            case CONE, FRUSTUM -> insideCircle(lx, lz, cx, cz, spec.radius() + 0.5);
            case PRISM -> insideRegularPolygon(lx, lz, cx, cz, spec.sides(), spec.radius());
        };
    }

    private static boolean insideVertical(ShapeSpec spec, int x, int y, int z, double cx, double cz, double rotRad) {
        double[] rz = rotateY(x, z, cx, cz, -rotRad);
        double lx = rz[0];
        double lz = rz[1];
        double t = spec.height() <= 1 ? 0 : y / (double) (spec.height() - 1);
        return switch (spec.kind()) {
            case BOX, CYLINDER, PRISM -> true;
            case CONE -> {
                double rAtY = spec.radius() * (1.0 - t);
                yield insideCircle(lx, lz, cx, cz, rAtY);
            }
            case FRUSTUM -> {
                double rAtY = spec.topRadius() + (spec.radius() - spec.topRadius()) * (1.0 - t);
                yield insideCircle(lx, lz, cx, cz, rAtY);
            }
        };
    }

    private static boolean isInteriorShell(ShapeSpec spec, int x, int y, int z, double cx, double cz, double rotRad) {
        int t = spec.wallThickness();
        for (int dx = -t; dx <= t; dx++) {
            for (int dz = -t; dz <= t; dz++) {
                for (int dy = -t; dy <= t; dy++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    int nx = x + dx;
                    int ny = y + dy;
                    int nz = z + dz;
                    if (nx < 0 || ny < 0 || nz < 0
                            || nx >= spec.width() || ny >= spec.height() || nz >= spec.depth()) {
                        return false;
                    }
                    if (!contains(spec, nx, nz, cx, cz, rotRad)) {
                        return false;
                    }
                    if (!insideVertical(spec, nx, ny, nz, cx, cz, rotRad)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean insideCircle(double x, double z, double cx, double cz, double radius) {
        double dx = x - cx;
        double dz = z - cz;
        return (dx * dx + dz * dz) <= radius * radius + 0.25;
    }

    private static boolean insideRegularPolygon(double x, double z, double cx, double cz, int sides, double radius) {
        double dx = x - cx;
        double dz = z - cz;
        double dist = Math.hypot(dx, dz);
        if (dist > radius + 0.5) {
            return false;
        }
        if (dist < 1e-6) {
            return true;
        }
        double angle = Math.atan2(dz, dx);
        double sector = (2.0 * Math.PI) / Math.max(3, sides);
        double boundaryRadius = radius * Math.cos(sector * 0.5) / Math.max(1e-6,
                Math.cos((angle % sector + sector) % sector - sector * 0.5));
        return dist <= boundaryRadius + 0.35;
    }

    private static double[] rotateY(double x, double z, double cx, double cz, double rad) {
        double dx = x - cx;
        double dz = z - cz;
        if (Math.abs(rad) < 1e-9) {
            return new double[]{x, z};
        }
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        return new double[]{cx + dx * cos - dz * sin, cz + dx * sin + dz * cos};
    }
}
