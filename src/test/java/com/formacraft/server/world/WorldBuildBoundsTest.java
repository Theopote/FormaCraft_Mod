package com.formacraft.server.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorldBuildBoundsTest {

    @Test
    void nullWorldUsesOverworldDefaults() {
        assertEquals(319, WorldBuildBounds.topYInclusive(null));
        assertEquals(319, WorldBuildBounds.clampY(null, 400));
        assertEquals(-64, WorldBuildBounds.clampY(null, -100));
        assertEquals(0, WorldBuildBounds.clampY(null, 0));
    }

    @Test
    void clampYRangeOrdersAndClips() {
        int[] r = WorldBuildBounds.clampYRange(null, 500, -80);
        assertEquals(-64, r[0]);
        assertEquals(319, r[1]);
        int[] empty = WorldBuildBounds.clampYRange(null, 400, 450);
        assertTrue(empty[0] > empty[1]);
    }

    @Test
    void terrainSourcesDoNotHardcodePre121Top() throws Exception {
        for (String rel : new String[]{
                "src/main/java/com/formacraft/server/terrain/TerrainSampling.java",
                "src/main/java/com/formacraft/server/terrain/TerrainAdaptationEngine.java",
                "src/main/java/com/formacraft/server/terrain/TerrainFit.java",
                "src/main/java/com/formacraft/server/terrain/TerrainShaper.java"
        }) {
            String src = java.nio.file.Files.readString(java.nio.file.Path.of(rel));
            assertFalse(src.contains("worldMaxY = 383"), rel);
            assertFalse(src.contains("WORLD_MAX_Y = 384"), rel);
            assertFalse(src.contains("maxY = 384"), rel);
        }
    }
}
