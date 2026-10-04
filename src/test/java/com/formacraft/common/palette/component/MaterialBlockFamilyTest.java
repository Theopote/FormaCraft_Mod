package com.formacraft.common.palette.component;

import com.formacraft.server.generation.structure.HouseMaterialResolver;
import com.formacraft.common.model.build.BuildingStyle;
import net.minecraft.block.Blocks;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class MaterialBlockFamilyTest {
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    @Test void deepslateBrickAndTileFamiliesStayDistinct() {
        assertEquals("minecraft:deepslate_brick_stairs", MaterialBlockFamily.stairs("minecraft:deepslate_bricks"));
        assertEquals("minecraft:deepslate_tile_slab", MaterialBlockFamily.slab("minecraft:deepslate_tiles"));
        assertEquals(Blocks.DEEPSLATE_TILE_STAIRS, HouseMaterialResolver.defaultRoofStairs(
                BuildingStyle.ASIAN, Blocks.DEEPSLATE_TILES.getDefaultState()).getBlock());
        assertEquals(Blocks.DEEPSLATE_BRICK_SLAB, HouseMaterialResolver.defaultRoofSlab(
                BuildingStyle.ASIAN, Blocks.DEEPSLATE_BRICKS.getDefaultState()).getBlock());
    }
    @Test void registeredWoodAndStoneShapesAndUnsupportedMaterials() {
        assertEquals("minecraft:birch_stairs", MaterialBlockFamily.stairs("minecraft:birch_planks"));
        assertEquals("minecraft:brick_slab", MaterialBlockFamily.slab("minecraft:bricks"));
        assertNull(MaterialBlockFamily.stairs("minecraft:white_concrete"));
        assertNull(MaterialBlockFamily.slab("minecraft:not_a_block"));
    }
}
