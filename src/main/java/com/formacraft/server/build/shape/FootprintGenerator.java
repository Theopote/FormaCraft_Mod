package com.formacraft.server.build.shape;

import com.formacraft.common.model.build.Footprint;
import com.formacraft.common.model.build.ShapeSpec;
import net.minecraft.util.math.BlockPos;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Generates footprint masks from shape specs.
 */
public final class FootprintGenerator {
    private FootprintGenerator() {}

    public record Result(Set<BlockPos> points, int width, int depth) {}

    public static Result generate(Footprint footprint) {
        if (footprint == null) return generateRect(8, 6);
        if (footprint.getShapeSpec() != null) {
            return generate(footprint.getShapeSpec());
        }
        String shape = footprint.getShape() != null ? footprint.getShape().trim().toLowerCase(java.util.Locale.ROOT) : "";
        if ("circle".equals(shape) && footprint.getRadius() > 0) {
            return generateCircle(footprint.getRadius());
        }
        int w = footprint.getWidth() > 0 ? footprint.getWidth() : 8;
        int d = footprint.getDepth() > 0 ? footprint.getDepth() : 6;
        return generateRect(w, d);
    }

    public static Result generate(ShapeSpec spec) {
        if (spec == null) return generateRect(8, 6);
        Map<String, Integer> p = (spec.params() != null) ? spec.params() : Map.of();
        Set<BlockPos> positions = new HashSet<>();

        ShapeSpec.ShapeType type = (spec.type() != null) ? spec.type() : ShapeSpec.ShapeType.RECTANGLE;
        switch (type) {
            case L_SHAPE -> generateLShape(positions, p);
            case U_SHAPE -> generateUShape(positions, p);
            case COURTYARD -> generateCourtyard(positions, p);
            case CIRCLE -> {
                int r = p.getOrDefault("radius", 8);
                return generateCircle(r);
            }
            case CROSS -> generateCross(positions, p);
            case RECTANGLE -> generateRectShape(positions, p);
        }

        if (spec.rotation() != 0) {
            positions = applyRotation(positions, spec.rotation());
        }

        positions = normalizeToMinCorner(positions);
        int width = computeWidth(positions);
        int depth = computeDepth(positions);
        return new Result(positions, width, depth);
    }

    private static Result generateRect(int w, int d) {
        Set<BlockPos> positions = new HashSet<>();
        addRect(positions, 0, 0, w, d);
        return new Result(positions, Math.max(1, w), Math.max(1, d));
    }

    private static void generateRectShape(Set<BlockPos> pos, Map<String, Integer> params) {
        int w = params.getOrDefault("width", 10);
        int d = params.getOrDefault("depth", 10);
        addRect(pos, 0, 0, w, d);
    }

    private static void generateLShape(Set<BlockPos> pos, Map<String, Integer> params) {
        int mainW = params.getOrDefault("main_width", 10);
        int mainD = params.getOrDefault("main_depth", 6);
        int wingW = params.getOrDefault("wing_width", 6);
        int wingD = params.getOrDefault("wing_depth", 6);

        addRect(pos, 0, 0, mainW, mainD);
        addRect(pos, 0, mainD, wingW, wingD);
    }

    private static void generateUShape(Set<BlockPos> pos, Map<String, Integer> params) {
        int w = params.getOrDefault("width", 15);
        int d = params.getOrDefault("depth", 10);
        int t = params.getOrDefault("wing_width", 5);

        addRect(pos, 0, d - t, w, t);
        addRect(pos, 0, 0, t, d - t);
        addRect(pos, w - t, 0, t, d - t);
    }

    private static void generateCourtyard(Set<BlockPos> pos, Map<String, Integer> params) {
        int w = params.getOrDefault("width", 20);
        int d = params.getOrDefault("depth", 20);
        int t = params.getOrDefault("wall_thickness", 5);

        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                boolean solid = (x < t) || (x >= w - t) || (z < t) || (z >= d - t);
                if (solid) {
                    pos.add(new BlockPos(x, 0, z));
                }
            }
        }
    }

    private static void generateCross(Set<BlockPos> pos, Map<String, Integer> params) {
        int len = params.getOrDefault("length", 20);
        int w = params.getOrDefault("width", 6);
        int center = len / 2;
        int halfW = w / 2;

        addRect(pos, center - halfW, 0, w, len);
        addRect(pos, 0, center - halfW, len, w);
    }

    private static Result generateCircle(int radius) {
        int r = Math.max(1, radius);
        int rSq = r * r;
        Set<BlockPos> pos = new HashSet<>();
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                if (x * x + z * z <= rSq) {
                    pos.add(new BlockPos(x + r, 0, z + r));
                }
            }
        }
        int size = r * 2 + 1;
        return new Result(pos, size, size);
    }

    private static void addRect(Set<BlockPos> set, int startX, int startZ, int width, int depth) {
        int w = Math.max(1, width);
        int d = Math.max(1, depth);
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                set.add(new BlockPos(startX + x, 0, startZ + z));
            }
        }
    }

    private static Set<BlockPos> applyRotation(Set<BlockPos> original, int degrees) {
        int d = degrees % 360;
        if (d < 0) d += 360;
        if (d == 0) return original;
        Set<BlockPos> rotated = new HashSet<>(original.size());
        for (BlockPos p : original) {
            int x = p.getX();
            int z = p.getZ();
            if (d == 90) {
                rotated.add(new BlockPos(-z, 0, x));
            } else if (d == 180) {
                rotated.add(new BlockPos(-x, 0, -z));
            } else if (d == 270) {
                rotated.add(new BlockPos(z, 0, -x));
            } else {
                rotated.add(p);
            }
        }
        return rotated;
    }

    private static Set<BlockPos> normalizeToMinCorner(Set<BlockPos> points) {
        if (points.isEmpty()) return points;
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        for (BlockPos p : points) {
            minX = Math.min(minX, p.getX());
            minZ = Math.min(minZ, p.getZ());
        }
        if (minX == 0 && minZ == 0) return points;
        Set<BlockPos> out = new HashSet<>(points.size());
        for (BlockPos p : points) {
            out.add(new BlockPos(p.getX() - minX, 0, p.getZ() - minZ));
        }
        return out;
    }

    private static int computeWidth(Set<BlockPos> points) {
        int maxX = 0;
        for (BlockPos p : points) {
            maxX = Math.max(maxX, p.getX());
        }
        return Math.max(1, maxX + 1);
    }

    private static int computeDepth(Set<BlockPos> points) {
        int maxZ = 0;
        for (BlockPos p : points) {
            maxZ = Math.max(maxZ, p.getZ());
        }
        return Math.max(1, maxZ + 1);
    }
}
