package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Vec3i;

/**
 * Domain-aware multi-mass spatial relation (Phase 02.3.3 Task B).
 * <p>
 * AABB is broad-phase only. When CSG/ResolvedMassPart domains are present, contact
 * type and intersection use real occupancy — never constant-section AABB alone.
 * Classification: GEOMETRY (not building-name heuristics).
 */
public enum MassDomainRelation {
    DISJOINT,
    FACE_CONTACT,
    EDGE_CONTACT,
    CORNER_CONTACT,
    PARTIAL_INTERSECT,
    CONTAINMENT,
    VERTICAL_STACK;

    /** Result of a pairwise query including optional shared-face axis. */
    public record Query(
            MassDomainRelation relation,
            /** Shared vertical face normal when FACE_CONTACT / shallow seam: EAST/WEST/NORTH/SOUTH; else null. */
            String sharedFace,
            int contactMinY,
            int contactMaxY,
            int contactCellCount
    ) {
        public static Query of(MassDomainRelation rel) {
            return new Query(rel, null, 0, 0, 0);
        }
    }

    /**
     * Classify two masses. Pass domains in envelope-local space with plan-space origins;
     * when either domain is null/empty, falls back to {@link MassSpatialRelation} AABB rules.
     */
    public static Query classify(
            ComponentFootprintUtil.Bounds aabbA,
            ComponentFootprintUtil.Bounds aabbB,
            MassBooleanComposition.CsgResult domainA,
            Vec3i originA,
            MassBooleanComposition.CsgResult domainB,
            Vec3i originB
    ) {
        if (aabbA == null || aabbB == null) return Query.of(DISJOINT);

        boolean hasDomain = domainA != null && !domainA.isEmpty()
                && domainB != null && !domainB.isEmpty();
        if (!hasDomain) {
            return fromAabbOnly(aabbA, aabbB);
        }

        Vec3i oa = originA == null ? new Vec3i(0, 0, 0) : originA;
        Vec3i ob = originB == null ? new Vec3i(0, 0, 0) : originB;

        // Broad phase: expand AABB by 1 so face-adjacent pairs are not skipped.
        if (!broadPhaseTouchOrOverlap(aabbA, aabbB)) {
            return Query.of(DISJOINT);
        }

        int overlapCells = 0;
        int faceAdj = 0;
        int edgeAdj = 0;
        int cornerAdj = 0;
        int contactMinY = Integer.MAX_VALUE;
        int contactMaxY = Integer.MIN_VALUE;
        int faceEast = 0, faceWest = 0, faceNorth = 0, faceSouth = 0;

        // Scan A's solid cells near B's AABB (domain narrow phase).
        int minX = Math.min(aabbA.minX(), aabbB.minX()) - 1;
        int maxX = Math.max(aabbA.maxX(), aabbB.maxX()) + 1;
        int minY = Math.min(aabbA.minY(), aabbB.minY()) - 1;
        int maxY = Math.max(aabbA.maxY(), aabbB.maxY()) + 1;
        int minZ = Math.min(aabbA.minZ(), aabbB.minZ()) - 1;
        int maxZ = Math.max(aabbA.maxZ(), aabbB.maxZ()) + 1;
        // Clamp scan to union of bounds ±1
        minX = Math.max(minX, Math.min(aabbA.minX(), aabbB.minX()) - 1);
        maxX = Math.min(maxX, Math.max(aabbA.maxX(), aabbB.maxX()) + 1);

        boolean aInB = true;
        boolean bInA = true;
        int aSolid = 0;
        int bSolid = 0;

        for (int y = minY; y < maxY; y++) {
            for (int x = minX; x < maxX; x++) {
                for (int z = minZ; z < maxZ; z++) {
                    boolean inA = domainContains(domainA, oa, x, y, z);
                    boolean inB = domainContains(domainB, ob, x, y, z);
                    if (inA) aSolid++;
                    if (inB) bSolid++;
                    if (inA && inB) {
                        overlapCells++;
                        contactMinY = Math.min(contactMinY, y);
                        contactMaxY = Math.max(contactMaxY, y + 1);
                    } else if (inA && !inB) {
                        if (!domainContains(domainB, ob, x, y, z)) {
                            // still outside B
                        }
                        if (!boundsContains(aabbB, x, y, z) && !faceAdjacentToDomain(domainB, ob, x, y, z)) {
                            // a cell far from B — weak evidence against containment of A in B
                        }
                    }
                }
            }
        }

        // Containment via AABB nest + no exterior solid of inner outside outer domain.
        boolean aabbAinB = aabbContained(aabbA, aabbB);
        boolean aabbBinA = aabbContained(aabbB, aabbA);
        if (aabbAinB || aabbBinA) {
            // Verify with domain samples on the inner AABB corners/edges.
            boolean nested = aabbAinB
                    ? allInnerSolidInOuter(aabbA, domainA, oa, domainB, ob)
                    : allInnerSolidInOuter(aabbB, domainB, ob, domainA, oa);
            if (nested && overlapCells > 0) {
                return new Query(CONTAINMENT, null,
                        contactMinY == Integer.MAX_VALUE ? 0 : contactMinY,
                        contactMaxY == Integer.MIN_VALUE ? 0 : contactMaxY,
                        overlapCells);
            }
        }

        if (overlapCells > 0) {
            // Vertical stack prefers Y-disjoint XZ-overlap when domains barely interpenetrate.
            boolean yOverlap = MassSpatialRelation.yOverlap(aabbA, aabbB);
            boolean xzOverlap = MassSpatialRelation.xzOverlap(aabbA, aabbB);
            if (xzOverlap && !yOverlap) {
                return new Query(VERTICAL_STACK, null, 0, 0, overlapCells);
            }
            return new Query(PARTIAL_INTERSECT, null,
                    contactMinY == Integer.MAX_VALUE ? 0 : contactMinY,
                    contactMaxY == Integer.MIN_VALUE ? 0 : contactMaxY,
                    overlapCells);
        }

        // No volumetric overlap — classify contact by adjacency of domain solids.
        for (int y = Math.max(aabbA.minY(), aabbB.minY());
             y < Math.min(aabbA.maxY(), aabbB.maxY()); y++) {
            for (int x = Math.min(aabbA.minX(), aabbB.minX()) - 1;
                 x < Math.max(aabbA.maxX(), aabbB.maxX()) + 1; x++) {
                for (int z = Math.min(aabbA.minZ(), aabbB.minZ()) - 1;
                     z < Math.max(aabbA.maxZ(), aabbB.maxZ()) + 1; z++) {
                    boolean inA = domainContains(domainA, oa, x, y, z);
                    if (!inA) continue;
                    int nFace = 0;
                    if (domainContains(domainB, ob, x + 1, y, z)) {
                        nFace++;
                        faceEast++;
                        contactMinY = Math.min(contactMinY, y);
                        contactMaxY = Math.max(contactMaxY, y + 1);
                    }
                    if (domainContains(domainB, ob, x - 1, y, z)) {
                        nFace++;
                        faceWest++;
                        contactMinY = Math.min(contactMinY, y);
                        contactMaxY = Math.max(contactMaxY, y + 1);
                    }
                    if (domainContains(domainB, ob, x, y, z + 1)) {
                        nFace++;
                        faceSouth++;
                        contactMinY = Math.min(contactMinY, y);
                        contactMaxY = Math.max(contactMaxY, y + 1);
                    }
                    if (domainContains(domainB, ob, x, y, z - 1)) {
                        nFace++;
                        faceNorth++;
                        contactMinY = Math.min(contactMinY, y);
                        contactMaxY = Math.max(contactMaxY, y + 1);
                    }
                    // Edge (XZ diagonal) without face
                    int nEdge = 0;
                    if (domainContains(domainB, ob, x + 1, y, z + 1)) nEdge++;
                    if (domainContains(domainB, ob, x + 1, y, z - 1)) nEdge++;
                    if (domainContains(domainB, ob, x - 1, y, z + 1)) nEdge++;
                    if (domainContains(domainB, ob, x - 1, y, z - 1)) nEdge++;
                    if (nFace > 0) {
                        faceAdj++;
                    } else if (nEdge > 0) {
                        edgeAdj++;
                        contactMinY = Math.min(contactMinY, y);
                        contactMaxY = Math.max(contactMaxY, y + 1);
                    } else {
                        // Corner: diagonal in 3D including Y
                        boolean corner =
                                domainContains(domainB, ob, x + 1, y + 1, z + 1)
                                        || domainContains(domainB, ob, x + 1, y + 1, z - 1)
                                        || domainContains(domainB, ob, x - 1, y + 1, z + 1)
                                        || domainContains(domainB, ob, x - 1, y + 1, z - 1)
                                        || domainContains(domainB, ob, x + 1, y - 1, z + 1)
                                        || domainContains(domainB, ob, x + 1, y - 1, z - 1)
                                        || domainContains(domainB, ob, x - 1, y - 1, z + 1)
                                        || domainContains(domainB, ob, x - 1, y - 1, z - 1);
                        if (corner) cornerAdj++;
                    }
                }
            }
        }

        if (faceAdj > 0) {
            String face = dominantFace(faceEast, faceWest, faceNorth, faceSouth);
            return new Query(FACE_CONTACT, face,
                    contactMinY == Integer.MAX_VALUE ? 0 : contactMinY,
                    contactMaxY == Integer.MIN_VALUE ? 0 : contactMaxY,
                    faceAdj);
        }
        if (edgeAdj > 0) {
            return new Query(EDGE_CONTACT, null,
                    contactMinY == Integer.MAX_VALUE ? 0 : contactMinY,
                    contactMaxY == Integer.MIN_VALUE ? 0 : contactMaxY,
                    edgeAdj);
        }
        if (cornerAdj > 0) {
            return Query.of(CORNER_CONTACT);
        }

        // Y-stack without contact cells
        if (MassSpatialRelation.xzOverlap(aabbA, aabbB)
                && !MassSpatialRelation.yOverlap(aabbA, aabbB)) {
            MassSpatialRelation aabbRel = MassSpatialRelation.classify(aabbA, aabbB, false);
            if (aabbRel == MassSpatialRelation.VERTICAL_STACK
                    || aabbRel == MassSpatialRelation.XZ_OVERLAP_Y_DISJOINT) {
                return Query.of(VERTICAL_STACK);
            }
        }
        return Query.of(DISJOINT);
    }

    private static Query fromAabbOnly(
            ComponentFootprintUtil.Bounds a,
            ComponentFootprintUtil.Bounds b
    ) {
        MassSpatialRelation rel = MassSpatialRelation.classify(a, b, false);
        return switch (rel) {
            case SEPARATED -> Query.of(DISJOINT);
            case CONTACTING -> {
                String face = inferAabbFace(a, b);
                if (face == null && isAabbCornerOnly(a, b)) {
                    yield Query.of(CORNER_CONTACT);
                }
                if (face == null && isAabbEdgeOnly(a, b)) {
                    yield Query.of(EDGE_CONTACT);
                }
                yield new Query(FACE_CONTACT, face, Math.max(a.minY(), b.minY()),
                        Math.min(a.maxY(), b.maxY()), 0);
            }
            case CONNECTION_SEAM, AABB3_INTERSECT -> Query.of(PARTIAL_INTERSECT);
            case VERTICAL_STACK, XZ_OVERLAP_Y_DISJOINT -> Query.of(VERTICAL_STACK);
            case INTENTIONAL_INTERLOCK -> Query.of(PARTIAL_INTERSECT);
        };
    }

    private static boolean broadPhaseTouchOrOverlap(
            ComponentFootprintUtil.Bounds a,
            ComponentFootprintUtil.Bounds b
    ) {
        return a.minX() <= b.maxX() + 1 && b.minX() <= a.maxX() + 1
                && a.minY() <= b.maxY() + 1 && b.minY() <= a.maxY() + 1
                && a.minZ() <= b.maxZ() + 1 && b.minZ() <= a.maxZ() + 1;
    }

    private static boolean domainContains(
            MassBooleanComposition.CsgResult domain,
            Vec3i origin,
            int x, int y, int z
    ) {
        return domain.contains(x - origin.x(), y - origin.y(), z - origin.z());
    }

    private static boolean faceAdjacentToDomain(
            MassBooleanComposition.CsgResult domain,
            Vec3i origin,
            int x, int y, int z
    ) {
        return domainContains(domain, origin, x + 1, y, z)
                || domainContains(domain, origin, x - 1, y, z)
                || domainContains(domain, origin, x, y + 1, z)
                || domainContains(domain, origin, x, y - 1, z)
                || domainContains(domain, origin, x, y, z + 1)
                || domainContains(domain, origin, x, y, z - 1);
    }

    private static boolean boundsContains(ComponentFootprintUtil.Bounds b, int x, int y, int z) {
        return x >= b.minX() && x < b.maxX() && y >= b.minY() && y < b.maxY()
                && z >= b.minZ() && z < b.maxZ();
    }

    private static boolean aabbContained(
            ComponentFootprintUtil.Bounds inner,
            ComponentFootprintUtil.Bounds outer
    ) {
        return inner.minX() >= outer.minX() && inner.maxX() <= outer.maxX()
                && inner.minY() >= outer.minY() && inner.maxY() <= outer.maxY()
                && inner.minZ() >= outer.minZ() && inner.maxZ() <= outer.maxZ();
    }

    private static boolean allInnerSolidInOuter(
            ComponentFootprintUtil.Bounds inner,
            MassBooleanComposition.CsgResult innerDomain,
            Vec3i innerOrigin,
            MassBooleanComposition.CsgResult outerDomain,
            Vec3i outerOrigin
    ) {
        // Sample a sparse grid of inner solids.
        int stepX = Math.max(1, (inner.maxX() - inner.minX()) / 4);
        int stepY = Math.max(1, (inner.maxY() - inner.minY()) / 4);
        int stepZ = Math.max(1, (inner.maxZ() - inner.minZ()) / 4);
        for (int y = inner.minY(); y < inner.maxY(); y += stepY) {
            for (int x = inner.minX(); x < inner.maxX(); x += stepX) {
                for (int z = inner.minZ(); z < inner.maxZ(); z += stepZ) {
                    if (domainContains(innerDomain, innerOrigin, x, y, z)
                            && !domainContains(outerDomain, outerOrigin, x, y, z)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static String dominantFace(int east, int west, int north, int south) {
        int best = Math.max(Math.max(east, west), Math.max(north, south));
        if (best <= 0) return null;
        if (east == best) return "EAST";
        if (west == best) return "WEST";
        if (south == best) return "SOUTH";
        return "NORTH";
    }

    private static String inferAabbFace(
            ComponentFootprintUtil.Bounds a,
            ComponentFootprintUtil.Bounds b
    ) {
        if (!MassSpatialRelation.yOverlap(a, b)) return null;
        // Face requires a positive span on the tangential axis (not a single corner/edge point).
        int spanZ = Math.min(a.maxZ(), b.maxZ()) - Math.max(a.minZ(), b.minZ());
        int spanX = Math.min(a.maxX(), b.maxX()) - Math.max(a.minX(), b.minX());
        if (a.maxX() == b.minX() && spanZ > 0) return "EAST";
        if (b.maxX() == a.minX() && spanZ > 0) return "WEST";
        if (a.maxZ() == b.minZ() && spanX > 0) return "SOUTH";
        if (b.maxZ() == a.minZ() && spanX > 0) return "NORTH";
        return null;
    }

    private static boolean isAabbCornerOnly(
            ComponentFootprintUtil.Bounds a,
            ComponentFootprintUtil.Bounds b
    ) {
        boolean xTouch = a.maxX() == b.minX() || b.maxX() == a.minX();
        boolean zTouch = a.maxZ() == b.minZ() || b.maxZ() == a.minZ();
        boolean yOverlap = MassSpatialRelation.yOverlap(a, b);
        return xTouch && zTouch && yOverlap
                && !MassSpatialRelation.xzOverlap(a, b);
    }

    private static boolean isAabbEdgeOnly(
            ComponentFootprintUtil.Bounds a,
            ComponentFootprintUtil.Bounds b
    ) {
        // Vertical edge: X and Z both touch without face span.
        return isAabbCornerOnly(a, b);
    }
}
