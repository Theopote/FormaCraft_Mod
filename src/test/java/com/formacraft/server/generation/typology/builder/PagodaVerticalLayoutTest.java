package com.formacraft.server.generation.typology.builder;

import com.formacraft.common.typology.TypologyGenerationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PagodaVerticalLayoutTest {

    @Test
    void threeStoreyDoubleEaveComfortableMinIsTwenty() {
        // Horizontal multi-tier eaves no longer add vertical eaveNeed rows.
        PagodaVerticalSemantics v = new PagodaVerticalSemantics(3, 2, 6, true);
        assertEquals(20, PagodaVerticalLayout.requiredMinHeight(v));
        assertEquals(13, PagodaVerticalLayout.absoluteMinHeight(v));
    }

    @Test
    void explicitHeightFarBelowAbsoluteMinThrowsWhenFloorsImplicit() {
        // floorsExplicit=false: a wildly short authored height still hard-fails.
        PagodaVerticalSemantics v = new PagodaVerticalSemantics(3, 2, 6, false);
        TypologyGenerationException ex = assertThrows(
                TypologyGenerationException.class,
                () -> PagodaVerticalLayout.plan("dense_eaves_pagoda", v, 26, true, 10, 4));
        assertEquals("E_PAGODA_SIZE", ex.code());
        assertTrue(ex.getMessage().contains("minimum feasible height=13"), ex.getMessage());
    }

    @Test
    void explicitHeightNearMissThrowsInsteadOfExpanding() {
        // Authored height is hard: floor_count cannot silently bump 12 → 13.
        PagodaVerticalSemantics v = new PagodaVerticalSemantics(3, 2, 6, true);
        TypologyGenerationException ex = assertThrows(
                TypologyGenerationException.class,
                () -> PagodaVerticalLayout.plan("dense_eaves_pagoda", v, 26, true, 12, 4));
        assertEquals("E_PAGODA_SIZE", ex.code());
        assertTrue(ex.getMessage().contains("minimum feasible height=13"), ex.getMessage());
    }

    @Test
    void comfortableHeightReservesFullFinial() {
        PagodaVerticalSemantics v = new PagodaVerticalSemantics(3, 2, 6, true);
        PagodaVerticalLayout.Result layout = PagodaVerticalLayout.plan(
                "dense_eaves_pagoda", v, 26, true, 26, 4);
        assertEquals(26, layout.plannedHeight());
        assertFalse(layout.compressed());
        assertEquals(PagodaVerticalLayout.FINIAL_HEIGHT_FULL, layout.finialHeight());
        assertEquals(layout.plannedHeight() - PagodaVerticalLayout.FINIAL_HEIGHT_FULL, layout.finialBaseY());
        // Last eave must meet the finial seat — no floating tip.
        int lastEaveY = layout.eaves().get(layout.eaves().size() - 1).y();
        assertEquals(layout.finialBaseY() - 1, lastEaveY);
    }

    @Test
    void softDefaultHeightExpandsWhenFloorsNeedMoreThanRegistryFortySeven() {
        // Nine-storey abs min = 9*3 + 4 = 31; soft 47 is already enough, but keep expand path.
        PagodaVerticalSemantics v = new PagodaVerticalSemantics(9, 2, 18, true);
        int abs = PagodaVerticalLayout.absoluteMinHeight(v);
        assertTrue(abs >= 31, "abs=" + abs);
        PagodaVerticalLayout.Result layout = PagodaVerticalLayout.plan(
                "dense_eaves_pagoda", v, 47, false, 47, 4);
        assertTrue(layout.plannedHeight() >= abs);
        assertEquals(9, layout.storeys().size());
        assertEquals(18, layout.eaves().size());
        int lastEaveY = layout.eaves().get(layout.eaves().size() - 1).y();
        assertEquals(layout.finialBaseY() - 1, lastEaveY);
    }

    @Test
    void eavesSitFlushOnStoreyCeilingWithNoInterStoreyGap() {
        PagodaVerticalSemantics v = new PagodaVerticalSemantics(3, 2, 6, true);
        PagodaVerticalLayout.Result layout = PagodaVerticalLayout.plan(
                "dense_eaves_pagoda", v, 26, true, 24, 4);
        assertEquals(layout.storeys().get(0).y0() + layout.storeys().get(0).height() - 1,
                layout.eaves().get(0).y());
        // Both tiers of storey-0 share the ceiling Y (horizontal 重檐).
        assertEquals(layout.eaves().get(0).y(), layout.eaves().get(1).y());
        // Next storey starts immediately above the ceiling — no empty shaft band.
        assertEquals(layout.storeys().get(0).y0() + layout.storeys().get(0).height(),
                layout.storeys().get(1).y0());
    }

    @Test
    void explicitHeightNineteenCompressesInsteadOfExpanding() {
        // Comfortable min is 20; authored 19 is still ≥ absolute 13 → compress finial.
        PagodaVerticalSemantics v = new PagodaVerticalSemantics(3, 2, 6, true);
        PagodaVerticalLayout.Result layout = PagodaVerticalLayout.plan(
                "dense_eaves_pagoda", v, 26, true, 19, 4);
        assertEquals(19, layout.plannedHeight());
        assertTrue(layout.compressed());
        assertEquals(PagodaVerticalLayout.FINIAL_HEIGHT_COMPACT, layout.finialHeight());
        assertEquals(3, layout.storeys().size());
        assertEquals(6, layout.eaves().size());
    }

    @Test
    void implicitHeightUsesSoftDefaultAtOrAboveRequired() {
        PagodaVerticalSemantics v = new PagodaVerticalSemantics(3, 2, 6, true);
        PagodaVerticalLayout.Result layout = PagodaVerticalLayout.plan(
                "dense_eaves_pagoda", v, 28, false, 0, 4);
        assertEquals(28, layout.plannedHeight());
        assertFalse(layout.compressed());
        assertEquals(PagodaVerticalLayout.FINIAL_HEIGHT_FULL, layout.finialHeight());
    }

    @Test
    void threeStoreyDoubleEaveFinialMeetsTopEaveAtTwentyFourTwentySixTwentyEight() {
        PagodaVerticalSemantics v = new PagodaVerticalSemantics(3, 2, 6, true);
        for (int h : new int[]{24, 26, 28}) {
            PagodaVerticalLayout.Result layout = PagodaVerticalLayout.plan(
                    "dense_eaves_pagoda", v, 26, true, h, 4);
            int lastEaveY = layout.eaves().get(layout.eaves().size() - 1).y();
            assertEquals(layout.finialBaseY() - 1, lastEaveY,
                    "height=" + h + ": finial must sit on top roof deck");
            assertEquals(3, layout.storeys().size());
            assertEquals(6, layout.eaves().size());
        }
    }
}
