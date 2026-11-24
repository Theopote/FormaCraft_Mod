package com.formacraft.common.generation.component.util;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.style.AestheticPassHints;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FacadeWindowTrimEmitterTest {
    @Test
    void classicTrimPlacesOutwardSillAndDoesNotOverwriteDoorBayAxis() {
        var opening = new ComponentWindowOrderDecorator.FacadeOpening(
                Direction.SOUTH, 0, 2, 3, 2, 4, true, true);
        List<BlockPatch> out = new ArrayList<>();
        ComponentWindowOrderDecorator.emitWindowOrder(
                out, opening, ComponentWindowOrderDecorator.OrderLevel.MEDIUM,
                "minecraft:stone_bricks", "minecraft:stone_brick_stairs", "minecraft:stone_brick_slab",
                AestheticPassHints.WindowTrim.CLASSIC, "minecraft:oak_trapdoor");
        assertFalse(out.isEmpty());
        // All trim is PLACE on z = fixed+1 (outward), never on door plane z=0 interior bay
        assertTrue(out.stream().allMatch(p -> BlockPatch.PLACE.equals(p.action())));
        assertTrue(out.stream().anyMatch(p -> p.dz() == 1 && p.dy() == 1)); // sill outward
        assertTrue(out.stream().noneMatch(p -> p.dz() == 0 && p.dx() >= 2 && p.dx() <= 3
                && p.dy() >= 2 && p.dy() <= 4)); // does not rewrite glass plane
    }

    @Test
    void noneTrimEmitsNothing() {
        var opening = new ComponentWindowOrderDecorator.FacadeOpening(
                Direction.EAST, 5, 1, 2, 1, 3, false, false);
        List<BlockPatch> out = new ArrayList<>();
        ComponentWindowOrderDecorator.emitWindowOrder(
                out, opening, ComponentWindowOrderDecorator.OrderLevel.FULL,
                "minecraft:stone_bricks", "minecraft:stone_brick_stairs", "minecraft:stone_brick_slab",
                AestheticPassHints.WindowTrim.NONE, null);
        assertTrue(out.isEmpty());
    }

    @Test
    void classicFullNarrowWindowPrefersShuttersOverSidePosts() {
        var opening = new ComponentWindowOrderDecorator.FacadeOpening(
                Direction.SOUTH, 0, 2, 3, 2, 3, true, true); // width 2
        List<BlockPatch> out = new ArrayList<>();
        ComponentWindowOrderDecorator.emitWindowOrder(
                out, opening, ComponentWindowOrderDecorator.OrderLevel.FULL,
                "minecraft:stone_bricks", "minecraft:stone_brick_stairs", "minecraft:stone_brick_slab",
                AestheticPassHints.WindowTrim.CLASSIC, "minecraft:oak_trapdoor");
        List<BlockPatch> sideCells = out.stream()
                .filter(p -> (p.dx() == 1 || p.dx() == 4) && p.dy() >= 2 && p.dy() <= 3 && p.dz() == 1)
                .toList();
        assertFalse(sideCells.isEmpty());
        assertTrue(sideCells.stream().allMatch(p -> p.targetBlock() != null && p.targetBlock().contains("trapdoor")),
                "narrow CLASSIC FULL must place shutters, not full-block side posts");
        assertTrue(sideCells.stream().noneMatch(p -> "minecraft:stone_bricks".equals(p.targetBlock())));
    }

    @Test
    void subVoxelStatesEncodeFacing() {
        String stairs = SubVoxelBlockStates.outwardStairs("minecraft:oak_stairs", Direction.SOUTH, true);
        assertNotNull(stairs);
        assertTrue(stairs.contains("half=top"));
        assertTrue(stairs.contains("facing=north")); // opposite of outward
        String shutter = SubVoxelBlockStates.shutterTrapdoor("minecraft:oak_trapdoor", Direction.EAST, true);
        assertTrue(shutter.contains("open=true"));
        assertTrue(shutter.contains("facing=east"));
    }
}
