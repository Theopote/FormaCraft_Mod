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

    @Test
    void snakeCaseFootprintShapeBeatsRegistryDefault() {
        var component = new com.formacraft.common.llm.dto.Component(
                "STRUCTURE",
                null,
                new com.formacraft.common.llm.dto.Vec3i(0, 0, 0),
                new com.formacraft.common.llm.dto.Dimensions(25, 25, 64),
                java.util.List.of("typology:dense_eaves_pagoda"),
                java.util.Map.of("footprint_shape", "square", "story_count", 7, "base_width", 25)
        );
        var merged = TypologyParamResolver.merge("dense_eaves_pagoda", component);
        assertEquals("square", merged.get("footprint"));
        assertEquals(7, merged.get("levels"));
        assertEquals(25, ((Number) merged.get("baseWidth")).intValue());
    }

    @Test
    void landmarkPresetIsSoftPriorPlanParamsWin() {
        var component = new com.formacraft.common.llm.dto.Component(
                "STRUCTURE",
                null,
                new com.formacraft.common.llm.dto.Vec3i(0, 0, 0),
                new com.formacraft.common.llm.dto.Dimensions(25, 25, 64),
                java.util.List.of("typology:dense_eaves_pagoda"),
                java.util.Map.of(
                        "reference_landmark", "giant_wild_goose_pagoda",
                        "footprint_shape", "square",
                        "base_width", 25,
                        "height", 64,
                        "story_count", 9
                )
        );
        var merged = TypologyParamResolver.merge("dense_eaves_pagoda", component);
        // Explicit plan/LLM attributes are not overwritten by the named landmark.
        assertEquals("square", merged.get("footprint"));
        assertEquals(9, merged.get("levels"));
        assertEquals(64, merged.get("height"));
        assertEquals(25, ((Number) merged.get("baseWidth")).intValue());
        // Soft fill only for omitted materials / niche rhythm.
        assertEquals("minecraft:bricks", merged.get("bodyBlock"));
        assertEquals("minecraft:brick_slab", merged.get("eaveBlock"));
        assertEquals("none", merged.get("niche_rhythm"));
    }

    @Test
    void landmarkPresetFillsGapsWhenPlanOmitsShape() {
        var component = new com.formacraft.common.llm.dto.Component(
                "STRUCTURE",
                null,
                new com.formacraft.common.llm.dto.Vec3i(0, 0, 0),
                new com.formacraft.common.llm.dto.Dimensions(20, 20, 40),
                java.util.List.of("typology:dense_eaves_pagoda"),
                java.util.Map.of("reference_landmark", "giant_wild_goose_pagoda")
        );
        var merged = TypologyParamResolver.merge("dense_eaves_pagoda", component);
        assertEquals("square", merged.get("footprint"));
        assertEquals(7, merged.get("levels"));
        assertEquals(17, merged.get("baseWidth"));
    }
}
