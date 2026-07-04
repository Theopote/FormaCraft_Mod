package com.formacraft.common.generation.component.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 纯函数回归：{@link ComponentFacadeStyler} 的墙体图案与立面构图语义。
 * 契约核心是"无配置/非外墙时原样返回"，避免默认路径回归。
 */
class ComponentFacadeStylerTest {

    private static final String WALL = "minecraft:stone_bricks";
    private static final String TRIM = "minecraft:polished_andesite";
    private static final String FOUND = "minecraft:cobblestone";

    @Test
    void nullWallIsPassedThrough() {
        assertNull(ComponentFacadeStyler.applyWallPattern(null, TRIM, FOUND, "gradient", 3, 10));
    }

    @Test
    void unknownOrBlankPatternReturnsWall() {
        assertEquals(WALL, ComponentFacadeStyler.applyWallPattern(WALL, TRIM, FOUND, null, 3, 10));
        assertEquals(WALL, ComponentFacadeStyler.applyWallPattern(WALL, TRIM, FOUND, "", 3, 10));
        assertEquals(WALL, ComponentFacadeStyler.applyWallPattern(WALL, TRIM, FOUND, "nope", 3, 10));
    }

    @Test
    void gradientUsesFoundationAtBaseAndTrimAtTop() {
        assertEquals(FOUND, ComponentFacadeStyler.applyWallPattern(WALL, TRIM, FOUND, "gradient", 1, 10));
        assertEquals(TRIM, ComponentFacadeStyler.applyWallPattern(WALL, TRIM, FOUND, "gradient", 8, 10));
        assertEquals(WALL, ComponentFacadeStyler.applyWallPattern(WALL, TRIM, FOUND, "gradient", 4, 10));
    }

    @Test
    void stripedBandsEveryThirdCourse() {
        assertEquals(TRIM, ComponentFacadeStyler.applyWallPattern(WALL, TRIM, FOUND, "striped", 3, 10));
        assertEquals(WALL, ComponentFacadeStyler.applyWallPattern(WALL, TRIM, FOUND, "striped", 4, 10));
    }

    @Test
    void randomStoneVariantIsDeterministicAndBounded() {
        Set<String> allowed = Set.of(WALL, "minecraft:cracked_stone_bricks", "minecraft:mossy_stone_bricks");
        String a = ComponentFacadeStyler.applyWallPattern(WALL, TRIM, FOUND, "random", 5, 12);
        String b = ComponentFacadeStyler.applyWallPattern(WALL, TRIM, FOUND, "random", 5, 12);
        assertEquals(a, b, "same inputs must yield same variant");
        assertTrue(allowed.contains(a), "unexpected random variant: " + a);
        // Non-stone walls are never rewritten by the random branch.
        assertEquals("minecraft:oak_planks",
                ComponentFacadeStyler.applyWallPattern("minecraft:oak_planks", TRIM, FOUND, "random", 5, 12));
    }

    @Test
    void profileIgnoredWhenNotExterior() {
        assertEquals(WALL, ComponentFacadeStyler.applyFacadeProfile(
                WALL, WALL, TRIM, FOUND, "vertical_pilasters",
                /*isExterior*/ false, /*isEdgeZ*/ true, 3, 2, 0, 10, 10, 4));
    }

    @Test
    void profileDoesNotClobberAlreadySubstitutedCell() {
        // current already diverged from wallId (e.g. a window/trim placed earlier) -> keep it
        assertEquals(TRIM, ComponentFacadeStyler.applyFacadeProfile(
                TRIM, WALL, TRIM, FOUND, "module_grid",
                true, true, 3, 3, 0, 10, 10, 4));
    }

    @Test
    void basePlinthOnlyAtFirstCourse() {
        assertEquals(FOUND, ComponentFacadeStyler.applyFacadeProfile(
                WALL, WALL, TRIM, FOUND, "base_plinth", true, true, 3, 1, 0, 10, 10, 4));
        assertEquals(WALL, ComponentFacadeStyler.applyFacadeProfile(
                WALL, WALL, TRIM, FOUND, "base_plinth", true, true, 3, 2, 0, 10, 10, 4));
    }

    @Test
    void verticalPilastersOnCadenceColumns() {
        // isEdgeZ=true -> pilasters keyed on x; x%3==0, interior, y>0 -> trim
        assertEquals(TRIM, ComponentFacadeStyler.applyFacadeProfile(
                WALL, WALL, TRIM, FOUND, "vertical_pilasters", true, true, 3, 2, 0, 10, 10, 4));
        assertEquals(WALL, ComponentFacadeStyler.applyFacadeProfile(
                WALL, WALL, TRIM, FOUND, "vertical_pilasters", true, true, 1, 2, 0, 10, 10, 4));
    }
}
