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
    void denseEavesPagodaHasOctagonDefaults() {
        StructuralTypologyRegistry.TypologyDef def = StructuralTypologyRegistry.getById("dense_eaves_pagoda");
        assertNotNull(def);
        assertEquals("VERTICAL_STACK", def.skeletonType());
        assertEquals("octagon", def.defaultParams().get("footprint"));
        assertEquals(13, def.defaultParams().get("levels"));
    }
}
