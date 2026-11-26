package com.formacraft.common.generation.component.util;

/**
 * Half-open AABB spatial relations for mass composition (Phase 02.1).
 * <p>
 * Bounds use exclusive max on every axis ({@link ComponentFootprintUtil.Bounds}).
 * Classification: GEOMETRY — not building-name heuristics.
 */
public enum MassSpatialRelation {
    /** No XZ and no Y overlap; not adjacent on a face. */
    SEPARATED,
    /** Share a face (touch) without volumetric overlap. */
    CONTACTING,
    /** XZ projections overlap; Y intervals are disjoint. */
    XZ_OVERLAP_Y_DISJOINT,
    /** Volumetric AABB intersection (XZ and Y). */
    AABB3_INTERSECT,
    /**
     * Shallow 3D seam (shared-wall / staggered compound join): AABB intersects but the smaller
     * horizontal overlap extent is ≤ {@link #CONNECTION_SEAM_CELLS}. Not a courtyard collision.
     */
    CONNECTION_SEAM,
    /** Nested/near-nested XZ with non-overlapping or stacked Y (podium/tower). */
    VERTICAL_STACK,
    /** Caller-marked intentional interlock (composition cue); not inferred from names alone. */
    INTENTIONAL_INTERLOCK;

    /** Max smaller-axis XZ overlap (cells) still treated as a connection seam, not a conflict. */
    public static final int CONNECTION_SEAM_CELLS = 2;

    /** True when XZ half-open intervals overlap. */
    public static boolean xzOverlap(ComponentFootprintUtil.Bounds a, ComponentFootprintUtil.Bounds b) {
        if (a == null || b == null) return false;
        return a.minX() < b.maxX() && b.minX() < a.maxX()
                && a.minZ() < b.maxZ() && b.minZ() < a.maxZ();
    }

    /** True when Y half-open intervals overlap. */
    public static boolean yOverlap(ComponentFootprintUtil.Bounds a, ComponentFootprintUtil.Bounds b) {
        if (a == null || b == null) return false;
        return a.minY() < b.maxY() && b.minY() < a.maxY();
    }

    /** True when AABBs intersect in 3D (required before conflict nudge). */
    public static boolean aabb3Intersect(ComponentFootprintUtil.Bounds a, ComponentFootprintUtil.Bounds b) {
        return xzOverlap(a, b) && yOverlap(a, b);
    }

    /** Face-adjacent (touch) without interior overlap on XZ or Y. */
    public static boolean contacting(ComponentFootprintUtil.Bounds a, ComponentFootprintUtil.Bounds b) {
        if (a == null || b == null) return false;
        if (aabb3Intersect(a, b)) return false;
        boolean xzTouch = a.minX() <= b.maxX() && b.minX() <= a.maxX()
                && a.minZ() <= b.maxZ() && b.minZ() <= a.maxZ()
                && !xzOverlap(a, b);
        boolean yTouch = a.minY() <= b.maxY() && b.minY() <= a.maxY()
                && !yOverlap(a, b);
        // Share a vertical face (XZ touch, Y overlap) or a horizontal face (Y touch, XZ overlap).
        if (xzTouch && yOverlap(a, b)) return true;
        if (yTouch && xzOverlap(a, b)) return true;
        return false;
    }

    private static boolean xzNearNested(ComponentFootprintUtil.Bounds inner, ComponentFootprintUtil.Bounds outer) {
        return inner.minX() >= outer.minX() - 1 && inner.maxX() <= outer.maxX() + 1
                && inner.minZ() >= outer.minZ() - 1 && inner.maxZ() <= outer.maxZ() + 1;
    }

    /**
     * Classify two plan-space bounds. Pass {@code intentionalInterlock=true} when the plan
     * explicitly asks for interlocking volumes (not name inference).
     */
    public static MassSpatialRelation classify(
            ComponentFootprintUtil.Bounds a,
            ComponentFootprintUtil.Bounds b,
            boolean intentionalInterlock
    ) {
        if (a == null || b == null) return SEPARATED;
        if (intentionalInterlock && aabb3Intersect(a, b)) {
            return INTENTIONAL_INTERLOCK;
        }
        boolean xz = xzOverlap(a, b);
        boolean y = yOverlap(a, b);
        if (xz && y) {
            int overlapX = Math.min(a.maxX(), b.maxX()) - Math.max(a.minX(), b.minX());
            int overlapZ = Math.min(a.maxZ(), b.maxZ()) - Math.max(a.minZ(), b.minZ());
            // Shared-wall / staggered join: one horizontal axis only penetrates a few cells.
            if (Math.min(overlapX, overlapZ) <= CONNECTION_SEAM_CELLS) {
                return CONNECTION_SEAM;
            }
            return AABB3_INTERSECT;
        }
        if (xz && !y) {
            if (xzNearNested(a, b) || xzNearNested(b, a)) {
                return VERTICAL_STACK;
            }
            return XZ_OVERLAP_Y_DISJOINT;
        }
        if (contacting(a, b)) {
            return CONTACTING;
        }
        return SEPARATED;
    }

    /** Relations that must never trigger footprint nudge. */
    public static boolean forbidsNudge(MassSpatialRelation rel) {
        return rel == SEPARATED
                || rel == CONTACTING
                || rel == CONNECTION_SEAM
                || rel == XZ_OVERLAP_Y_DISJOINT
                || rel == VERTICAL_STACK
                || rel == INTENTIONAL_INTERLOCK;
    }
}
