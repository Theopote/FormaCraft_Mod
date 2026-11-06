package com.formacraft.server.generation.typology.builder;

import com.formacraft.common.typology.TypologyGenerationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PagodaVerticalLayoutTest {

    @Test
    void threeStoreyDoubleEaveComfortableMinIsTwentySix() {
        PagodaVerticalSemantics v = new PagodaVerticalSemantics(3, 2, 6, true);
        assertEquals(26, PagodaVerticalLayout.requiredMinHeight(v));
        assertEquals(19, PagodaVerticalLayout.absoluteMinHeight(v));
    }

    @Test
    void explicitHeightFarBelowAbsoluteMinThrowsWhenFloorsImplicit() {
        // floorsExplicit=false: a wildly short authored height still hard-fails.
        PagodaVerticalSemantics v = new PagodaVerticalSemantics(3, 2, 6, false);
        TypologyGenerationException ex = assertThrows(
                TypologyGenerationException.class,
                () -> PagodaVerticalLayout.plan("dense_eaves_pagoda", v, 26, true, 10, 4));
        assertEquals("E_PAGODA_SIZE", ex.code());
        assertTrue(ex.getMessage().contains("minimum feasible height=19"), ex.getMessage());
    }

    @Test
    void explicitHeightNearMissThrowsInsteadOfExpanding() {
        // Authored height is hard: floor_count cannot silently bump 18 → 19.
        PagodaVerticalSemantics v = new PagodaVerticalSemantics(3, 2, 6, true);
        TypologyGenerationException ex = assertThrows(
                TypologyGenerationException.class,
                () -> PagodaVerticalLayout.plan("dense_eaves_pagoda", v, 26, true, 18, 4));
        assertEquals("E_PAGODA_SIZE", ex.code());
        assertTrue(ex.getMessage().contains("minimum feasible height=19"), ex.getMessage());
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
    }

    @Test
    void eavesSitFlushOnStoreyCeiling() {
        PagodaVerticalSemantics v = new PagodaVerticalSemantics(3, 1, 3, true);
        PagodaVerticalLayout.Result layout = PagodaVerticalLayout.plan(
                "dense_eaves_pagoda", v, 26, true, 24, 4);
        assertEquals(layout.storeys().get(0).y0() + layout.storeys().get(0).height() - 1,
                layout.eaves().get(0).y());
        assertEquals(layout.eaves().get(0).y() + 1, layout.storeys().get(1).y0());
    }

    @Test
    void explicitHeightTwentyFourCompressesInsteadOfExpanding() {
        PagodaVerticalSemantics v = new PagodaVerticalSemantics(3, 2, 6, true);
        PagodaVerticalLayout.Result layout = PagodaVerticalLayout.plan(
                "dense_eaves_pagoda", v, 26, true, 24, 4);
        assertEquals(24, layout.plannedHeight());
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
}
