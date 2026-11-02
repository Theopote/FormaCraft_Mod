package com.formacraft.common.typology;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StructuralTypologyRegistryTest {

    @Test
    void loadsMigrationMap() {
        assertEquals("dense_eaves_pagoda", StructuralTypologyRegistry.typologyForLegacyModule("famen_pagoda"));
        assertEquals("tailiang_timber_hall", StructuralTypologyRegistry.typologyForLegacyModule("foguang_temple_hall"));
    }

    @Test
    void resolvesInterpreterId() {
        assertEquals("dense_eaves_pagoda", StructuralTypologyRegistry.resolveInterpreterId("dense_eaves_pagoda"));
        assertEquals("tailiang_timber_hall", StructuralTypologyRegistry.resolveInterpreterId("tailiang_timber_hall"));
        assertEquals("radial_terrace_hall", StructuralTypologyRegistry.resolveInterpreterId("radial_terrace_hall"));
    }

    @Test
    void denseEavesPagodaHasOctagonDefaultsWithoutPinnedLevels() {
        StructuralTypologyRegistry.TypologyDef def = StructuralTypologyRegistry.getById("dense_eaves_pagoda");
        assertNotNull(def);
        assertEquals("VERTICAL_STACK", def.skeletonType());
        assertEquals("octagon", def.defaultParams().get("footprint"));
        // floor_count/levels stay out of defaults so implicit tall min is not floorsExplicit.
        assertFalse(def.defaultParams().containsKey("levels"));
        assertFalse(def.defaultParams().containsKey("floor_count"));
        assertEquals(1, ((Number) def.defaultParams().get("eaves_per_level")).intValue());
    }

    @Test
    void tibetanStupaIsRegisteredDistinctFromDenseEaves() {
        StructuralTypologyRegistry.TypologyDef stupa = StructuralTypologyRegistry.getById("tibetan_stupa");
        assertNotNull(stupa);
        assertEquals("tibetan_stupa", stupa.interpreterId());
        assertNotEquals(
                StructuralTypologyRegistry.getById("dense_eaves_pagoda").interpreterId(),
                stupa.interpreterId());
    }
}
