package com.formacraft.common.generation.component.util;

import java.util.Map;

/** Equivalent explicit spellings of a wall-free frame. */
public final class OpenFramePolicy {
    private OpenFramePolicy() {}

    public static boolean isOpen(Map<String, Object> p) {
        if (p == null) return false;
        if (Boolean.TRUE.equals(p.get("open")) || Boolean.TRUE.equals(p.get("open_pavilion"))
                || "open".equalsIgnoreCase(String.valueOf(p.get("wall_style")))) return true;
        boolean noWalls = "none".equalsIgnoreCase(String.valueOf(p.get("wall_style")))
                || "none".equalsIgnoreCase(String.valueOf(p.get("wall_type")));
        return noWalls && p.get("open_sides") instanceof java.util.Collection<?> sides && sides.size() >= 2;
    }

    /**
     * Pavilion posts: rectangle AABB corners, or {@code sides}-gon samples / footprint vertices
     * when the plan is circular / cut-corner (AABB corners sit outside the footprint).
     */
    public static boolean isPost(ResolvedFootprint footprint, int localX, int localZ,
                                 int width, int depth, Map<String, Object> params) {
        if (footprint == null || !footprint.contains(localX, localZ)) return false;
        if (isFullyRectangular(footprint, width, depth)) {
            return (localX == 0 || localX == width - 1) && (localZ == 0 || localZ == depth - 1);
        }
        int sides = ComponentParamParsers.intParam(params, 0, "sides");
        if (sides >= 3) {
            return isAngularRimPost(footprint, localX, localZ, width, depth, sides);
        }
        return exteriorVertex(footprint, localX, localZ);
    }

    private static boolean isFullyRectangular(ResolvedFootprint footprint, int width, int depth) {
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                if (!footprint.contains(x, z)) return false;
            }
        }
        return true;
    }

    private static boolean exteriorVertex(ResolvedFootprint footprint, int x, int z) {
        int empty = 0;
        if (!footprint.contains(x + 1, z)) empty++;
        if (!footprint.contains(x - 1, z)) empty++;
        if (!footprint.contains(x, z + 1)) empty++;
        if (!footprint.contains(x, z - 1)) empty++;
        return empty >= 2;
    }

    /** One post per side: farthest occupied footprint cell near that bearing. */
    private static boolean isAngularRimPost(ResolvedFootprint footprint,
                                            int localX, int localZ, int width, int depth, int sides) {
        double cx = (width - 1) / 2.0;
        double cz = (depth - 1) / 2.0;
        for (int i = 0; i < sides; i++) {
            double angle = -Math.PI / 2 + i * (2 * Math.PI / sides);
            int pickX = -1, pickZ = -1;
            double pickR = -1;
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    if (!footprint.contains(x, z)) continue;
                    double dx = x - cx;
                    double dz = z - cz;
                    double r = Math.hypot(dx, dz);
                    if (r < 0.5) continue;
                    double delta = Math.abs(normalizeAngle(Math.atan2(dz, dx) - angle));
                    if (delta > Math.PI / sides) continue;
                    if (r > pickR) {
                        pickR = r;
                        pickX = x;
                        pickZ = z;
                    }
                }
            }
            if (pickR >= 0 && pickX == localX && pickZ == localZ) return true;
        }
        return false;
    }

    private static double normalizeAngle(double a) {
        while (a > Math.PI) a -= 2 * Math.PI;
        while (a < -Math.PI) a += 2 * Math.PI;
        return a;
    }
}
