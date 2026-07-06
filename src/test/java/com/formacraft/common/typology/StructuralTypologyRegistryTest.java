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
    void resolvesLegacyModuleForTypology() {
        assertEquals("famen_pagoda", StructuralTypologyRegistry.legacyModuleForTypology("dense_eaves_pagoda"));
        assertEquals("foguang_temple_hall", StructuralTypologyRegistry.legacyModuleForTypology("tailiang_timber_hall"));
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
