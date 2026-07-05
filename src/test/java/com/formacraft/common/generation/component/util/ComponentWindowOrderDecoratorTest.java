package com.formacraft.common.generation.component.util;

import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentWindowOrderDecoratorTest {

    @Test
    void emitWindowOrder_addsSillLintelAndPediment() {
        ComponentWindowOrderDecorator.FacadeOpening opening = new ComponentWindowOrderDecorator.FacadeOpening(
                Direction.SOUTH, 0, 5, 7, 2, 4, true, true);
        List<BlockPatch> patches = new ArrayList<>();
        ComponentWindowOrderDecorator.emitWindowOrder(
                patches,
                opening,
                ComponentWindowOrderDecorator.OrderLevel.FULL,
                "minecraft:stone_bricks",
                "minecraft:stone_brick_stairs",
                "minecraft:stone_brick_slab"
        );
        assertTrue(patches.stream().anyMatch(p -> p.targetBlock().contains("slab") && p.dy() == 1));
        assertTrue(patches.stream().anyMatch(p -> p.targetBlock().contains("stairs") && p.dy() == 5));
        assertTrue(patches.stream().anyMatch(p -> p.targetBlock().contains("stairs") && p.dy() == 6));
    }

    @Test
    void inferSlabBlock_derivesFromStairsMapping() {
        assertEquals("minecraft:stone_brick_slab",
                ComponentWindowOrderDecorator.inferSlabBlock("minecraft:stone_bricks"));
    }

    @Test
    void clusterGroupsAdjacentGlassOnSouthFace() {
        Set<long[]> glass = new HashSet<>();
        glass.add(ComponentWindowOrderDecorator.packCell(5, 2, 0));
        glass.add(ComponentWindowOrderDecorator.packCell(6, 2, 0));
        glass.add(ComponentWindowOrderDecorator.packCell(7, 2, 0));
        List<ComponentWindowOrderDecorator.FacadeOpening> openings = ComponentWindowOrderDecorator.clusterFacadeOpenings(
                glass, 0, 12, 0, 8, 0, 0, 6, 0, Direction.SOUTH);
        assertTrue(openings.stream().anyMatch(o -> o.axisMin() == 5 && o.axisMax() == 7 && o.yMin() == 2));
    }
}
