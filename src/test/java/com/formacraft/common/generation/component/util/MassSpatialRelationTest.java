package com.formacraft.common.generation.component.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MassSpatialRelationTest {

    private static ComponentFootprintUtil.Bounds box(int x0, int y0, int z0, int w, int h, int d) {
        return new ComponentFootprintUtil.Bounds(x0, y0, z0, x0 + w, y0 + h, z0 + d);
    }

    @Test
    void contactingFacesAreNotAabb3() {
        var a = box(0, 0, 0, 4, 4, 4);
        var b = box(4, 0, 0, 4, 4, 4); // shares the X=4 face
        assertFalse(MassSpatialRelation.aabb3Intersect(a, b));
        assertEquals(MassSpatialRelation.CONTACTING, MassSpatialRelation.classify(a, b, false));
        assertTrue(MassSpatialRelation.forbidsNudge(MassSpatialRelation.CONTACTING));
    }

    @Test
    void xzOverlapYDisjointIsNotConflict() {
        // Partial XZ overlap (not nested) + Y gap → XZ_OVERLAP_Y_DISJOINT, never nudge.
        var a = box(0, 0, 0, 10, 2, 10);
        var b = box(6, 4, 6, 10, 8, 10);
        assertTrue(MassSpatialRelation.xzOverlap(a, b));
        assertFalse(MassSpatialRelation.yOverlap(a, b));
        assertEquals(
                MassSpatialRelation.XZ_OVERLAP_Y_DISJOINT,
                MassSpatialRelation.classify(a, b, false));
        assertTrue(MassSpatialRelation.forbidsNudge(MassSpatialRelation.XZ_OVERLAP_Y_DISJOINT));
    }

    @Test
    void nestedYDisjointIsVerticalStack() {
        var base = box(0, 0, 0, 12, 4, 12);
        var tier = box(2, 4, 2, 8, 4, 8);
        assertEquals(MassSpatialRelation.VERTICAL_STACK, MassSpatialRelation.classify(base, tier, false));
    }

    @Test
    void volumetricOverlapIsAabb3() {
        var a = box(0, 0, 0, 8, 6, 8);
        var b = box(4, 2, 4, 8, 6, 8);
        assertTrue(MassSpatialRelation.aabb3Intersect(a, b));
        assertEquals(MassSpatialRelation.AABB3_INTERSECT, MassSpatialRelation.classify(a, b, false));
        assertFalse(MassSpatialRelation.forbidsNudge(MassSpatialRelation.AABB3_INTERSECT));
    }

    @Test
    void shallowSharedWallIsConnectionSeamNotConflict() {
        // 1-cell X penetration, deep Z overlap — keep/tower shared wall / staggered join.
        var hall = box(-6, 0, -5, 13, 9, 11);
        var tower = box(6, 0, -4, 8, 16, 8);
        assertTrue(MassSpatialRelation.aabb3Intersect(hall, tower));
        assertEquals(MassSpatialRelation.CONNECTION_SEAM, MassSpatialRelation.classify(hall, tower, false));
        assertTrue(MassSpatialRelation.forbidsNudge(MassSpatialRelation.CONNECTION_SEAM));
    }

    @Test
    void intentionalInterlockCueWins() {
        var a = box(0, 0, 0, 8, 6, 8);
        var b = box(4, 2, 4, 8, 6, 8);
        assertEquals(
                MassSpatialRelation.INTENTIONAL_INTERLOCK,
                MassSpatialRelation.classify(a, b, true));
        assertTrue(MassSpatialRelation.forbidsNudge(MassSpatialRelation.INTENTIONAL_INTERLOCK));
    }
}
