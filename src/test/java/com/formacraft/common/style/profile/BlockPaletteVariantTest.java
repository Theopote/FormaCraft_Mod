package com.formacraft.common.style.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 纯函数回归：{@link BlockPalette#pickWall(long)} / {@link BlockPalette#pickRoof(long)} 的
 * 确定性变体选择。核心不变量：无变体时退化为基础材质；有变体时取值恒在 {base}∪variants 内且可复现。
 */
class BlockPaletteVariantTest {

    private static BlockPalette palette() {
        BlockPalette p = new BlockPalette();
        p.wall = "minecraft:stone_bricks";
        p.roof = "minecraft:deepslate_tiles";
        return p;
    }

    @Test
    void noVariantsFallsBackToBase() {
        BlockPalette p = palette();
        for (long seed = 0; seed < 100; seed++) {
            assertEquals(p.wall, p.pickWall(seed));
            assertEquals(p.roof, p.pickRoof(seed));
        }
    }

    @Test
    void pickIsDeterministic() {
        BlockPalette p = palette();
        p.wallVariants = List.of("minecraft:cracked_stone_bricks", "minecraft:mossy_stone_bricks");
        for (long seed : new long[] {0, 1, 7, 42, 1234567, -99}) {
            assertEquals(p.pickWall(seed), p.pickWall(seed), "seed " + seed);
        }
    }

    @Test
    void pickStaysWithinBasePlusVariants() {
        BlockPalette p = palette();
        p.wallVariants = List.of("minecraft:cracked_stone_bricks", "minecraft:mossy_stone_bricks");
        Set<String> allowed = new HashSet<>(p.wallVariants);
        allowed.add(p.wall);
        Set<String> seen = new HashSet<>();
        for (long seed = 0; seed < 500; seed++) {
            String picked = p.pickWall(seed);
            assertTrue(allowed.contains(picked), "out-of-set pick: " + picked);
            seen.add(picked);
        }
        // The base id participates as an implicit member, so across many seeds it should appear.
        assertTrue(seen.contains(p.wall), "base material never picked across 500 seeds");
        assertTrue(seen.size() >= 2, "variant selection looks degenerate: " + seen);
    }

    @Test
    void blankBaseSelectsOnlyVariants() {
        BlockPalette p = new BlockPalette();
        p.wall = "  ";
        p.wallVariants = List.of("minecraft:andesite", "minecraft:diorite");
        for (long seed = 0; seed < 200; seed++) {
            String picked = p.pickWall(seed);
            assertTrue(p.wallVariants.contains(picked), "blank base must yield a variant, got: " + picked);
        }
    }

    @Test
    void emptyVariantListFallsBackToBase() {
        BlockPalette p = palette();
        p.wallVariants = List.of();
        assertEquals(p.wall, p.pickWall(12345));
    }
}
