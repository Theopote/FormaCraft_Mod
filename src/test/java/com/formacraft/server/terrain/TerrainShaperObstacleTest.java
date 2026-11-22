package com.formacraft.server.terrain;

import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TerrainShaperObstacleTest {
    @BeforeAll
    static void bootstrap() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void grassBlockSurfaceIsNotClearedAsObstacle() {
        assertFalse(TerrainShaper.isObstacle(Blocks.GRASS_BLOCK.getDefaultState()));
        assertFalse(TerrainShaper.isObstacle(Blocks.DIRT.getDefaultState()));
        assertFalse(TerrainShaper.isObstacle(Blocks.STONE.getDefaultState()));
    }

    @Test
    void canopyAndReplaceableVegetationAreObstacles() {
        assertTrue(TerrainShaper.isObstacle(Blocks.OAK_LOG.getDefaultState()));
        assertTrue(TerrainShaper.isObstacle(Blocks.OAK_LEAVES.getDefaultState()));
        assertTrue(TerrainShaper.isObstacle(Blocks.CHERRY_LOG.getDefaultState()));
        assertTrue(TerrainShaper.isObstacle(Blocks.CHERRY_LEAVES.getDefaultState()));
        assertTrue(TerrainShaper.isObstacle(Blocks.SHORT_GRASS.getDefaultState()));
        assertTrue(TerrainShaper.isObstacle(Blocks.DANDELION.getDefaultState()));
        assertTrue(TerrainShaper.isObstacle(Blocks.SNOW.getDefaultState()));
        assertTrue(TerrainShaper.isObstacle(Blocks.VINE.getDefaultState()));
    }

    @Test
    void fluidsAndAirAreNotObstacles() {
        assertFalse(TerrainShaper.isObstacle(Blocks.AIR.getDefaultState()));
        assertFalse(TerrainShaper.isObstacle(Blocks.WATER.getDefaultState()));
    }
}
