package com.formacraft.common.generation.component.util;

import com.formacraft.common.geometry.shape.ShapeLibrary;
import com.formacraft.common.llm.dto.Vec3i;

import java.util.ArrayList;
import java.util.List;

/**
 * Domain-aware multi-mass spatial relation (Phase 02.3.3 / 02.3.3.1 Task C).
 * <p>
 * AABB = broad phase only. Narrow phase iterates the <em>smaller</em> domain's voxels and
 * probes the other indexed domain — never enumerates the union AABB volume.
 * Classification: GEOMETRY.
 */
public enum MassDomainRelation {
    DISJOINT,
    FACE_CONTACT,
    EDGE_CONTACT,
    CORNER_CONTACT,
    PARTIAL_INTERSECT,
    CONTAINMENT,
    VERTICAL_STACK;

    public record Query(
            MassDomainRelation relation,
            String sharedFace,
            int contactMinY,
            int contactMaxY,
            int contactCellCount,
            /** True when domains were present and used for narrow-phase authority. */
            boolean domainAuthoritative
    ) {
        public static Query of(MassDomainRelation rel) {
            return new Query(rel, null, 0, 0, 0, false);
        }

        public static Query domain(
                MassDomainRelation rel, String face, int minY, int maxY, int cells
        ) {
            return new Query(rel, face, minY, maxY, cells, true);
        }
    }

    /**
     * @param domainA envelope-local CSG; {@code null} = unknown; empty = legal empty solid
     * @param originA plan-space origin of domainA local (0,0,0)
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

        // Legal empty domain — never invents shared wall / open from AABB.
        if (domainA != null && domainA.isEmpty()) return Query.domain(DISJOINT, null, 0, 0, 0);
        if (domainB != null && domainB.isEmpty()) return Query.domain(DISJOINT, null, 0, 0, 0);

        boolean hasDomain = domainA != null && domainB != null;
        if (!hasDomain) {
            // Unknown domain: AABB broad-only — no delete authority (domainAuthoritative=false).
            return fromAabbOnly(aabbA, aabbB);
        }

        if (!broadPhaseTouchOrOverlap(aabbA, aabbB)) {
            return Query.domain(DISJOINT, null, 0, 0, 0);
        }

        Vec3i oa = originA == null ? new Vec3i(0, 0, 0) : originA;
        Vec3i ob = originB == null ? new Vec3i(0, 0, 0) : originB;

        // Iterate smaller domain voxels.
        boolean aSmaller = domainA.voxels().size() <= domainB.voxels().size();
        MassBooleanComposition.CsgResult small = aSmaller ? domainA : domainB;
        MassBooleanComposition.CsgResult large = aSmaller ? domainB : domainA;
        Vec3i os = aSmaller ? oa : ob;
        Vec3i ol = aSmaller ? ob : oa;

        int overlapCells = 0;
        int faceAdj = 0, edgeAdj = 0, cornerAdj = 0;
        int faceEast = 0, faceWest = 0, faceNorth = 0, faceSouth = 0;
        int contactMinY = Integer.MAX_VALUE;
        int contactMaxY = Integer.MIN_VALUE;

        for (ShapeLibrary.Voxel v : small.voxels()) {
            int x = v.x() + os.x();
            int y = v.y() + os.y();
            int z = v.z() + os.z();
            if (domainContains(large, ol, x, y, z)) {
                overlapCells++;
                contactMinY = Math.min(contactMinY, y);
                contactMaxY = Math.max(contactMaxY, y + 1);
                continue;
            }
            int nFace = 0;
            if (domainContains(large, ol, x + 1, y, z)) {
                nFace++;
                faceEast++;
                touchY(y, contactMinY, contactMaxY);
                contactMinY = Math.min(contactMinY == Integer.MAX_VALUE ? y : contactMinY, y);
                contactMaxY = Math.max(contactMaxY == Integer.MIN_VALUE ? y + 1 : contactMaxY, y + 1);
            }
            if (domainContains(large, ol, x - 1, y, z)) {
                nFace++;
                faceWest++;
                contactMinY = Math.min(contactMinY == Integer.MAX_VALUE ? y : contactMinY, y);
                contactMaxY = Math.max(contactMaxY == Integer.MIN_VALUE ? y + 1 : contactMaxY, y + 1);
            }
            if (domainContains(large, ol, x, y, z + 1)) {
                nFace++;
                faceSouth++;
                contactMinY = Math.min(contactMinY == Integer.MAX_VALUE ? y : contactMinY, y);
                contactMaxY = Math.max(contactMaxY == Integer.MIN_VALUE ? y + 1 : contactMaxY, y + 1);
            }
            if (domainContains(large, ol, x, y, z - 1)) {
                nFace++;
                faceNorth++;
                contactMinY = Math.min(contactMinY == Integer.MAX_VALUE ? y : contactMinY, y);
                contactMaxY = Math.max(contactMaxY == Integer.MIN_VALUE ? y + 1 : contactMaxY, y + 1);
            }
            if (nFace > 0) {
                faceAdj++;
                continue;
            }
            int nEdge = 0;
            if (domainContains(large, ol, x + 1, y, z + 1)) nEdge++;
            if (domainContains(large, ol, x + 1, y, z - 1)) nEdge++;
            if (domainContains(large, ol, x - 1, y, z + 1)) nEdge++;
            if (domainContains(large, ol, x - 1, y, z - 1)) nEdge++;
            // Vertical edges
            if (domainContains(large, ol, x + 1, y + 1, z)) nEdge++;
            if (domainContains(large, ol, x - 1, y + 1, z)) nEdge++;
            if (domainContains(large, ol, x, y + 1, z + 1)) nEdge++;
            if (domainContains(large, ol, x, y + 1, z - 1)) nEdge++;
            if (domainContains(large, ol, x + 1, y - 1, z)) nEdge++;
            if (domainContains(large, ol, x - 1, y - 1, z)) nEdge++;
            if (domainContains(large, ol, x, y - 1, z + 1)) nEdge++;
            if (domainContains(large, ol, x, y - 1, z - 1)) nEdge++;
            if (nEdge > 0) {
                edgeAdj++;
                contactMinY = Math.min(contactMinY == Integer.MAX_VALUE ? y : contactMinY, y);
                contactMaxY = Math.max(contactMaxY == Integer.MIN_VALUE ? y + 1 : contactMaxY, y + 1);
                continue;
            }
            boolean corner =
                    domainContains(large, ol, x + 1, y + 1, z + 1)
                            || domainContains(large, ol, x + 1, y + 1, z - 1)
                            || domainContains(large, ol, x - 1, y + 1, z + 1)
                            || domainContains(large, ol, x - 1, y + 1, z - 1)
                            || domainContains(large, ol, x + 1, y - 1, z + 1)
                            || domainContains(large, ol, x + 1, y - 1, z - 1)
                            || domainContains(large, ol, x - 1, y - 1, z + 1)
                            || domainContains(large, ol, x - 1, y - 1, z - 1);
            if (corner) cornerAdj++;
        }

        // Exact containment: every solid of A in B (or reverse).
        if (aabbContained(aabbA, aabbB) || exactContained(domainA, oa, domainB, ob)) {
            if (exactContained(domainA, oa, domainB, ob) && overlapCells > 0) {
                return Query.domain(CONTAINMENT, null,
                        contactMinY == Integer.MAX_VALUE ? 0 : contactMinY,
                        contactMaxY == Integer.MIN_VALUE ? 0 : contactMaxY,
                        overlapCells);
            }
        }
        if (aabbContained(aabbB, aabbA) || exactContained(domainB, ob, domainA, oa)) {
            if (exactContained(domainB, ob, domainA, oa) && overlapCells > 0) {
                return Query.domain(CONTAINMENT, null,
                        contactMinY == Integer.MAX_VALUE ? 0 : contactMinY,
                        contactMaxY == Integer.MIN_VALUE ? 0 : contactMaxY,
                        overlapCells);
            }
        }

        if (overlapCells > 0) {
            boolean yOverlap = MassSpatialRelation.yOverlap(aabbA, aabbB);
            boolean xzOverlap = MassSpatialRelation.xzOverlap(aabbA, aabbB);
            if (xzOverlap && !yOverlap) {
                return Query.domain(VERTICAL_STACK, null, 0, 0, overlapCells);
            }
            return Query.domain(PARTIAL_INTERSECT, null,
                    contactMinY == Integer.MAX_VALUE ? 0 : contactMinY,
                    contactMaxY == Integer.MIN_VALUE ? 0 : contactMaxY,
                    overlapCells);
        }

        if (faceAdj > 0) {
            // Remap face counts into A→B direction (small may be B).
            String face = dominantFace(faceEast, faceWest, faceNorth, faceSouth);
            if (!aSmaller && face != null) {
                face = opposite(face);
            }
            return Query.domain(FACE_CONTACT, face,
                    contactMinY == Integer.MAX_VALUE ? 0 : contactMinY,
                    contactMaxY == Integer.MIN_VALUE ? 0 : contactMaxY,
                    faceAdj);
        }
        if (edgeAdj > 0) {
            return Query.domain(EDGE_CONTACT, null,
                    contactMinY == Integer.MAX_VALUE ? 0 : contactMinY,
                    contactMaxY == Integer.MIN_VALUE ? 0 : contactMaxY,
                    edgeAdj);
        }
        if (cornerAdj > 0) {
            return Query.domain(CORNER_CONTACT, null, 0, 0, cornerAdj);
        }
        if (MassSpatialRelation.xzOverlap(aabbA, aabbB)
                && !MassSpatialRelation.yOverlap(aabbA, aabbB)) {
            return Query.domain(VERTICAL_STACK, null, 0, 0, 0);
        }
        return Query.domain(DISJOINT, null, 0, 0, 0);
    }

    /** Exact: every voxel of inner is in outer. */
    public static boolean exactContained(
            MassBooleanComposition.CsgResult inner,
            Vec3i innerOrigin,
            MassBooleanComposition.CsgResult outer,
            Vec3i outerOrigin
    ) {
        if (inner == null || inner.isEmpty() || outer == null) return false;
        Vec3i oi = innerOrigin == null ? new Vec3i(0, 0, 0) : innerOrigin;
        Vec3i oo = outerOrigin == null ? new Vec3i(0, 0, 0) : outerOrigin;
        for (ShapeLibrary.Voxel v : inner.voxels()) {
            if (!domainContains(outer, oo, v.x() + oi.x(), v.y() + oi.y(), v.z() + oi.z())) {
                return false;
            }
        }
        return true;
    }

    private static void touchY(int y, int min, int max) {
        // no-op helper placeholder — contact min/max updated inline
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
                if (face == null && isAabbCornerOnly(a, b)) {
                    yield Query.of(EDGE_CONTACT);
                }
                yield new Query(FACE_CONTACT, face, Math.max(a.minY(), b.minY()),
                        Math.min(a.maxY(), b.maxY()), 0, false);
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

    private static boolean aabbContained(
            ComponentFootprintUtil.Bounds inner,
            ComponentFootprintUtil.Bounds outer
    ) {
        return inner.minX() >= outer.minX() && inner.maxX() <= outer.maxX()
                && inner.minY() >= outer.minY() && inner.maxY() <= outer.maxY()
                && inner.minZ() >= outer.minZ() && inner.maxZ() <= outer.maxZ();
    }

    private static String dominantFace(int east, int west, int north, int south) {
        int best = Math.max(Math.max(east, west), Math.max(north, south));
        if (best <= 0) return null;
        if (east == best) return "EAST";
        if (west == best) return "WEST";
        if (south == best) return "SOUTH";
        return "NORTH";
    }

    private static String opposite(String face) {
        if (face == null) return null;
        return switch (face) {
            case "EAST" -> "WEST";
            case "WEST" -> "EAST";
            case "NORTH" -> "SOUTH";
            case "SOUTH" -> "NORTH";
            default -> null;
        };
    }

    private static String inferAabbFace(
            ComponentFootprintUtil.Bounds a,
            ComponentFootprintUtil.Bounds b
    ) {
        if (!MassSpatialRelation.yOverlap(a, b)) return null;
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
        return xTouch && zTouch && MassSpatialRelation.yOverlap(a, b)
                && !MassSpatialRelation.xzOverlap(a, b);
    }

    /** Collect plan-space face-adjacent contact cells of A toward B (for openings / shared walls). */
    public static List<Vec3i> faceContactCellsA(
            MassBooleanComposition.CsgResult domainA,
            Vec3i originA,
            MassBooleanComposition.CsgResult domainB,
            Vec3i originB,
            String faceTowardB
    ) {
        List<Vec3i> out = new ArrayList<>();
        if (domainA == null || domainA.isEmpty() || domainB == null || domainB.isEmpty()) {
            return out;
        }
        Vec3i oa = originA == null ? new Vec3i(0, 0, 0) : originA;
        Vec3i ob = originB == null ? new Vec3i(0, 0, 0) : originB;
        int dx = 0, dz = 0;
        if ("EAST".equalsIgnoreCase(faceTowardB)) dx = 1;
        else if ("WEST".equalsIgnoreCase(faceTowardB)) dx = -1;
        else if ("SOUTH".equalsIgnoreCase(faceTowardB)) dz = 1;
        else if ("NORTH".equalsIgnoreCase(faceTowardB)) dz = -1;
        else return out;
        for (ShapeLibrary.Voxel v : domainA.voxels()) {
            int x = v.x() + oa.x();
            int y = v.y() + oa.y();
            int z = v.z() + oa.z();
            if (domainContains(domainB, ob, x + dx, y, z + dz)) {
                out.add(new Vec3i(x, y, z));
            }
        }
        return out;
    }
}
