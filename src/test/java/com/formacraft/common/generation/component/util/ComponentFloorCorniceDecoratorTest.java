package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Layout;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Vec3i;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentFloorCorniceDecoratorTest {

    @Test
    void floorBoundaryYs_stepByFloorHeight() {
        var ys = ComponentFloorCorniceDecorator.computeFloorBoundaryYs(12, 4);
        assertTrue(ys.get(3));
        assertTrue(ys.get(7));
        assertTrue(ys.get(11));
        assertFalse(ys.get(4));
    }

    @Test
    void inferStairsBlock_mapsBrickBlocksToSingularBrickStairs() {
        assertEquals("minecraft:stone_brick_stairs",
                ComponentFloorCorniceDecorator.inferStairsBlock("minecraft:stone_bricks"));
        assertEquals("minecraft:mossy_stone_brick_stairs",
                ComponentFloorCorniceDecorator.inferStairsBlock("minecraft:mossy_stone_bricks"));
        assertEquals("minecraft:nether_brick_stairs",
                ComponentFloorCorniceDecorator.inferStairsBlock("minecraft:nether_bricks"));
        assertEquals("minecraft:brick_stairs",
                ComponentFloorCorniceDecorator.inferStairsBlock("minecraft:bricks"));
    }

    @Test
    void corniceStairUsesInvertedHalfTop() {
        String block = ComponentFloorCorniceDecorator.corniceStairBlock(
                "minecraft:stone_bricks", Direction.SOUTH);
        assertTrue(block.contains("half=top"));
        assertTrue(block.contains("facing=south"));
        assertTrue(block.contains("stone_brick_stairs"));
    }

    @Test
    void shouldApplyForClassicalTypologyHints() {
        Map<String, Object> hints = Map.of("typology", "classical_monument", "floor_cornice", true);
        LlmPlan plan = new LlmPlan(
                null, null, null, null,
                new Layout(null, false, List.of()),
                List.of(), null, null,
                hints, null, null, null, null, null, null, null, null, null, null
        );
        assertTrue(ComponentFloorCorniceDecorator.shouldApply(plan));
    }

    @Test
    void resolveFloorHeightFromMassParams() {
        Component mass = new Component(
                "MASS_MAIN",
                "s0",
                new Vec3i(0, 0, 0),
                new Dimensions(13, 10, 12),
                List.of(),
                Map.of("floor_height", 4)
        );
        LlmPlan plan = new LlmPlan(
                null, null, null, null,
                new Layout(null, false, List.of()),
                List.of(mass), null, null,
                null, null, null, null, null, null, null, null, null, null
        );
        assertEquals(4, ComponentFloorCorniceDecorator.resolveFloorHeight(plan, 12));
    }
}
