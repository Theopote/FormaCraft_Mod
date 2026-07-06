package com.formacraft.common.typology;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TypologyParamResolverTest {

    @Test
    void mergeAppliesRegistryDefaultsWithoutLandmark() {
        var component = new com.formacraft.common.llm.dto.Component(
                "STRUCTURE",
                null,
                new com.formacraft.common.llm.dto.Vec3i(0, 0, 0),
                new com.formacraft.common.llm.dto.Dimensions(10, 10, 47),
                java.util.List.of("typology:dense_eaves_pagoda"),
                java.util.Map.of("levels", 9)
        );
        var merged = TypologyParamResolver.merge("dense_eaves_pagoda", component);
        assertEquals("dense_eaves_pagoda", merged.get("typology_id"));
        assertEquals(9, merged.get("levels"));
        assertEquals("octagon", merged.get("footprint"));
        assertFalse(merged.containsKey("landmark"));
        assertFalse(merged.containsKey("module_id"));
    }
}
