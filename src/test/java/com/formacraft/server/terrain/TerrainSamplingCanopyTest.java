package com.formacraft.server.terrain;

import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TerrainSamplingCanopyTest {
    @BeforeAll
    static void bootstrap() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void canopyMaterialsAreNotBearingGround() {
        assertTrue(TerrainSampling.isCanopyMaterial(Blocks.OAK_LOG.getDefaultState()));
        assertTrue(TerrainSampling.isCanopyMaterial(Blocks.OAK_LEAVES.getDefaultState()));
        assertTrue(TerrainSampling.isCanopyMaterial(Blocks.SPRUCE_LEAVES.getDefaultState()));
        assertFalse(TerrainSampling.isCanopyMaterial(Blocks.GRASS_BLOCK.getDefaultState()));
        assertFalse(TerrainSampling.isCanopyMaterial(Blocks.STONE.getDefaultState()));
        assertFalse(TerrainSampling.isCanopyMaterial(Blocks.WATER.getDefaultState()));
    }

    @Test
    void terrainPackagesNoLongerReferenceWorldSurfaceHeightmap() throws Exception {
        // Guardrail: forest float regressions come back when WORLD_SURFACE sneaks into terrain/*.
        for (String name : new String[]{
                "com.formacraft.server.terrain.TerrainFit",
                "com.formacraft.server.terrain.TerrainAdaptationEngine",
                "com.formacraft.server.terrain.TerrainShaper",
                "com.formacraft.server.terrain.BuildingLandingPlanner"
        }) {
            String path = "src/main/java/" + name.replace('.', '/') + ".java";
            String src = java.nio.file.Files.readString(java.nio.file.Path.of(path));
            assertFalse(src.contains("WORLD_SURFACE"), name + " still references WORLD_SURFACE");
        }
    }
}
