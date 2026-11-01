package com.formacraft.common.component.transform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockStateStringUtilPropertyTest {

    @Test
    void withPropertyMergesFacingOntoHalfTopStairs() {
        assertEquals(
                "minecraft:oak_stairs[facing=south,half=top]",
                BlockStateStringUtil.withFacing("minecraft:oak_stairs[half=top]", "south"));
    }

    @Test
    void withPropertyOverwritesFacingKeepsShapeAndWaterlogged() {
        assertEquals(
                "minecraft:dark_oak_stairs[facing=west,shape=outer_left,waterlogged=true]",
                BlockStateStringUtil.withProperty(
                        BlockStateStringUtil.withFacing(
                                "minecraft:dark_oak_stairs[facing=east,shape=outer_left,waterlogged=true]",
                                "west"),
                        "shape",
                        "outer_left"));
    }
}
