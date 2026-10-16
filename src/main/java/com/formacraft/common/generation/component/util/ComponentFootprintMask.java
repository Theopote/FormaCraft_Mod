package com.formacraft.common.generation.component.util;

import com.formacraft.common.compiler.semantic.SemanticComponent;

import java.util.Map;

/**
 * Thin adapter over {@link ResolvedFootprint} for roof / seam / coverage consumers.
 * Plan occupancy authority lives in ResolvedFootprint — do not re-derive courtyard geometry here.
 */
public final class ComponentFootprintMask {

    /** @deprecated use {@link ResolvedFootprint.FootprintShape} */
    public enum FootprintShape {
        RECTANGLE,
        CIRCLE,
        ROUNDED_RECT
    }

    /** @deprecated use {@link ResolvedFootprint.PlanPattern} */
    public enum PlanPattern {
        NONE,
        CROSS,
        CUT_CORNERS,
        L_SHAPE,
        COURTYARD
    }

    private final ResolvedFootprint footprint;

    private ComponentFootprintMask(ResolvedFootprint footprint) {
        this.footprint = footprint;
    }

    public static ComponentFootprintMask from(SemanticComponent semantic, Map<String, Object> params, int width, int depth) {
        return new ComponentFootprintMask(ResolvedFootprint.resolve(semantic, params, width, depth));
    }

    public ResolvedFootprint footprint() {
        return footprint;
    }

    public boolean contains(int x, int z) {
        return footprint.contains(x, z);
    }

    public boolean isCourtyardHole(int x, int z) {
        return footprint.isCourtyardHole(x, z);
    }

    /** True when every cell in the width×depth AABB is occupied (gable seal prerequisite). */
    public boolean isFullyRectangular() {
        for (int x = 0; x < footprint.width(); x++) {
            for (int z = 0; z < footprint.depth(); z++) {
                if (!contains(x, z)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Occupied cell plus outward normal toward an empty neighbor. */
    public record BoundaryEdge(int x, int z, int outwardDx, int outwardDz, boolean courtyard) {}

    /**
     * Collect 4-neighbor boundary edges. Courtyard = empty neighbor still inside AABB;
     * exterior = empty neighbor outside AABB or outside footprint at the bbox perimeter.
     */
    public java.util.List<BoundaryEdge> boundaryEdges() {
        var out = new java.util.ArrayList<BoundaryEdge>();
        int width = footprint.width();
        int depth = footprint.depth();
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                if (!contains(x, z)) {
                    continue;
                }
                for (int[] d : dirs) {
                    int nx = x + d[0];
                    int nz = z + d[1];
                    boolean insideAabb = nx >= 0 && nz >= 0 && nx < width && nz < depth;
                    if (insideAabb && contains(nx, nz)) {
                        continue;
                    }
                    boolean courtyard = insideAabb;
                    out.add(new BoundaryEdge(x, z, d[0], d[1], courtyard));
                }
            }
        }
        return out;
    }

    public java.util.List<BoundaryEdge> exteriorEdgeCells() {
        return boundaryEdges().stream().filter(e -> !e.courtyard()).toList();
    }

    public java.util.List<BoundaryEdge> courtyardEdgeCells() {
        return boundaryEdges().stream().filter(BoundaryEdge::courtyard).toList();
    }

    /**
     * 屋顶是否应落在此 mass 局部坐标（含合法飞檐外挑）。
     */
    public boolean shouldPlaceRoof(int localX, int localZ, int overhang) {
        if (contains(localX, localZ)) {
            return true;
        }
        if (overhang <= 0) {
            return false;
        }
        for (int ring = 1; ring <= overhang; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    int fx = localX - dx;
                    int fz = localZ - dz;
                    if (!contains(fx, fz) || !isExteriorCell(fx, fz)) {
                        continue;
                    }
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isExteriorCell(int x, int z) {
        if (!contains(x, z)) {
            return false;
        }
        return !contains(x + 1, z) || !contains(x - 1, z) || !contains(x, z + 1) || !contains(x, z - 1);
    }
}
