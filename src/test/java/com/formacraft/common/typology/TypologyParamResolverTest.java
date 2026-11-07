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
    void registryDefaultHeightIsNotPlanAuthored() {
        var component = new com.formacraft.common.llm.dto.Component(
                "STRUCTURE",
                null,
                new com.formacraft.common.llm.dto.Vec3i(0, 0, 0),
                new com.formacraft.common.llm.dto.Dimensions(10, 10, 47),
                java.util.List.of("typology:dense_eaves_pagoda"),
                java.util.Map.of("floor_count", 9, "eaves_per_level", 2)
        );
        var merged = TypologyParamResolver.merge("dense_eaves_pagoda", component);
        assertTrue(merged.containsKey("height"), "registry should still supply soft height");
        assertFalse(TypologyParamResolver.isPlanAuthored(merged, "height", "towerHeight"),
                "typology default height must not be treated as USER/PLAN hard constraint");
        assertTrue(TypologyParamResolver.isPlanAuthored(merged, "floor_count", "levels"));
    }

    @Test
    void llmInferredHeightIsPlanAuthoredButNotUserHardConstraint() {
        var component = new com.formacraft.common.llm.dto.Component(
                "STRUCTURE",
                null,
                new com.formacraft.common.llm.dto.Vec3i(0, 0, 0),
                new com.formacraft.common.llm.dto.Dimensions(10, 10, 28),
                java.util.List.of("typology:dense_eaves_pagoda"),
                java.util.Map.of("floor_count", 3, "height", 28)
        );
        var merged = TypologyParamResolver.merge("dense_eaves_pagoda", component);
        assertTrue(TypologyParamResolver.isPlanAuthored(merged, "height", "towerHeight"));
        assertFalse(TypologyParamResolver.isUserHardConstraint(merged, "height", "towerHeight"),
                "LLM-filled height must not be treated as player hard constraint");
        assertEquals(28, ((Number) merged.get("height")).intValue());
    }

    @Test
    void userExplicitHeightIsHardConstraint() {
        var component = new com.formacraft.common.llm.dto.Component(
                "STRUCTURE",
                null,
                new com.formacraft.common.llm.dto.Vec3i(0, 0, 0),
                new com.formacraft.common.llm.dto.Dimensions(10, 10, 28),
                java.util.List.of("typology:dense_eaves_pagoda"),
                java.util.Map.of(
                        "floor_count", 3,
                        "height", 28,
                        "design_sources", java.util.Map.of("height", "USER_EXPLICIT"))
        );
        var merged = TypologyParamResolver.merge("dense_eaves_pagoda", component);
        assertTrue(TypologyParamResolver.isUserHardConstraint(merged, "height", "towerHeight"));
        assertEquals(28, ((Number) merged.get("height")).intValue());
    }

    @Test
    void planTowerHeightOverwritesRegistryDefaultHeightValueAndSource() {
        // Registry default height=47 must not win when only towerHeight=28 is PLAN-authored.
        var component = new com.formacraft.common.llm.dto.Component(
                "STRUCTURE",
                null,
                new com.formacraft.common.llm.dto.Vec3i(0, 0, 0),
                new com.formacraft.common.llm.dto.Dimensions(9, 9, 28),
                java.util.List.of("typology:dense_eaves_pagoda"),
                java.util.Map.of("floor_count", 3, "towerHeight", 28)
        );
        var merged = TypologyParamResolver.merge("dense_eaves_pagoda", component);
        assertEquals(28, ((Number) merged.get("height")).intValue(),
                "canonical height must take PLAN towerHeight, not registry 47");
        assertEquals(28, ((Number) merged.get("towerHeight")).intValue());
        assertTrue(TypologyParamResolver.isPlanAuthored(merged, "height"));
        assertTrue(TypologyParamResolver.isPlanAuthored(merged, "towerHeight"));
        // TypologyParams prefers height when both keys exist.
        var tp = new TypologyParams(merged);
        assertEquals(28, tp.intVal("height", "towerHeight", 47, 1, 120));
    }

    @Test
    void conflictingPlanHeightAndTowerHeightKeepsCanonicalHeight() {
        var component = new com.formacraft.common.llm.dto.Component(
                "STRUCTURE",
                null,
                new com.formacraft.common.llm.dto.Vec3i(0, 0, 0),
                new com.formacraft.common.llm.dto.Dimensions(9, 9, 30),
                java.util.List.of("typology:dense_eaves_pagoda"),
                java.util.Map.of("height", 30, "towerHeight", 24)
        );
        var merged = TypologyParamResolver.merge("dense_eaves_pagoda", component);
        assertEquals(30, ((Number) merged.get("height")).intValue());
        assertEquals(30, ((Number) merged.get("towerHeight")).intValue(),
                "towerHeight must sync to canonical height on PLAN conflict");
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
    void floorCountPromotesToLevels() {
        var component = new com.formacraft.common.llm.dto.Component(
                "STRUCTURE",
                null,
                new com.formacraft.common.llm.dto.Vec3i(0, 0, 0),
                new com.formacraft.common.llm.dto.Dimensions(7, 7, 21),
                java.util.List.of("typology:dense_eaves_pagoda"),
                java.util.Map.of("floor_count", 3)
        );
        var merged = TypologyParamResolver.merge("dense_eaves_pagoda", component);
        assertEquals(3, merged.get("levels"));
        assertEquals(3, merged.get("floor_count"));
    }

    @Test
    void eaveTiersDoesNotAliasIntoFloorCount() {
        var component = new com.formacraft.common.llm.dto.Component(
                "STRUCTURE",
                null,
                new com.formacraft.common.llm.dto.Vec3i(0, 0, 0),
                new com.formacraft.common.llm.dto.Dimensions(7, 7, 21),
                java.util.List.of("typology:dense_eaves_pagoda"),
                java.util.Map.of("floor_count", 3, "eave_tiers", 6, "eaves_per_level", 2)
        );
        var merged = TypologyParamResolver.merge("dense_eaves_pagoda", component);
        assertEquals(3, merged.get("floor_count"));
        assertEquals(3, merged.get("levels"));
        assertEquals(6, merged.get("eave_tiers"));
        assertEquals(2, merged.get("eaves_per_level"));
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
        assertEquals(9, merged.get("floor_count"));
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

    @Test
    void wallBlockAndRoofBlockOverwriteTypologyDefaultMaterials() {
        var component = new com.formacraft.common.llm.dto.Component(
                "STRUCTURE",
                null,
                new com.formacraft.common.llm.dto.Vec3i(0, 0, 0),
                new com.formacraft.common.llm.dto.Dimensions(11, 11, 40),
                java.util.List.of("typology:dense_eaves_pagoda"),
                java.util.Map.of(
                        "wall_block", "minecraft:deepslate_bricks",
                        "roof_block", "minecraft:deepslate_tiles",
                        "floor_count", 5
                )
        );
        var merged = TypologyParamResolver.merge("dense_eaves_pagoda", component);
        assertEquals("minecraft:deepslate_bricks", merged.get("bodyBlock"));
        assertEquals("minecraft:deepslate_tiles", merged.get("eaveBlock"));
    }

    @Test
    void doubleEavesBeatsRegistryDefaultSingleEave() {
        var component = new com.formacraft.common.llm.dto.Component(
                "STRUCTURE",
                null,
                new com.formacraft.common.llm.dto.Vec3i(0, 0, 0),
                new com.formacraft.common.llm.dto.Dimensions(7, 7, 24),
                java.util.List.of("typology:dense_eaves_pagoda"),
                java.util.Map.of("floor_count", 3, "double_eaves", true)
        );
        var merged = TypologyParamResolver.merge("dense_eaves_pagoda", component);
        assertEquals(2, ((Number) merged.get("eaves_per_level")).intValue());
    }
}
