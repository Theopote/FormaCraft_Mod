package com.formacraft.common.detail;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetailRuleBlockResolverTest {

    @Test
    void inferSlabBlockFromWallBlock() {
        assertEquals("minecraft:stone_brick_slab",
                DetailRuleBlockResolver.inferSlabBlock("minecraft:stone_bricks"));
        assertEquals("minecraft:brick_slab",
                DetailRuleBlockResolver.inferSlabBlock("minecraft:bricks"));
    }

    @Test
    void wallFilterRejectsGlassAndStairs() {
        assertTrue(DetailRuleBlockResolver.matchesBlockFilter("minecraft:stone_bricks", "wall"));
        assertTrue(!DetailRuleBlockResolver.matchesBlockFilter("minecraft:glass", "wall"));
        assertTrue(!DetailRuleBlockResolver.matchesBlockFilter("minecraft:stone_brick_stairs", "wall"));
    }
}
