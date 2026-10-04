package com.formacraft.server.compiler;

import com.formacraft.common.compiler.postprocess.PostProcessContext.BuildingVolume;
import com.formacraft.common.generation.component.util.ComponentFootprintUtil.Bounds;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.assembly.AssemblyCirculationConstraints;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExteriorCirculationValidatorTest {
    private final List<BuildingVolume> volumes = List.of(
            new BuildingVolume("house", new Bounds(10, 2, 20, 16, 10, 26), 4),
            new BuildingVolume("house", new Bounds(16, 2, 22, 20, 7, 26), 4));
    private BlockPatch stone(BlockPos p) {
        return new BlockPatch(BlockPatch.PLACE, p.getX(), p.getY(), p.getZ(), "minecraft:stone_bricks");
    }
    private List<AssemblyCirculationConstraints.Flight> flight(BlockPos p, boolean clear) {
        return List.of(new AssemblyCirculationConstraints.Flight(clear ? Set.of() : Set.of(p),
                clear ? Set.of(p) : Set.of()));
    }
    @Test void rejectsBothTreadsAndClearanceOverExistingExterior() {
        MinecraftRegistryTestBootstrap.initialize();
        var p = new BlockPos(10, 5, 23);
        for (boolean clear : List.of(false, true)) {
            var collision = ExteriorCirculationValidator.findCollision(List.of(stone(p)), flight(p, clear), volumes);
            assertEquals(p, collision.orElseThrow().position());
            assertEquals(clear ? "clearance" : "tread/support", collision.get().role());
        }
    }
    @Test void allowsDoorwayLastWriteAirAndInteriorFloorOpening() {
        MinecraftRegistryTestBootstrap.initialize();
        var door = new BlockPos(10, 3, 23);
        var removal = new BlockPatch(BlockPatch.REMOVE, 10, 3, 23, "minecraft:air");
        assertTrue(ExteriorCirculationValidator.findCollision(List.of(stone(door), removal), flight(door, true), volumes).isEmpty());
        assertTrue(ExteriorCirculationValidator.findCollision(List.of(), flight(door, false), volumes).isEmpty());
        var inside = new BlockPos(12, 6, 23);
        assertTrue(ExteriorCirculationValidator.findCollision(List.of(stone(inside)), flight(inside, true), volumes).isEmpty());
    }
    @Test void usesHeightAwareUnionAndShiftedFlightCoordinates() {
        MinecraftRegistryTestBootstrap.initialize();
        var shared = new BlockPos(15, 4, 23);
        assertTrue(ExteriorCirculationValidator.findCollision(List.of(stone(shared)), flight(shared, true), volumes).isEmpty());
        var upper = new BlockPos(15, 8, 23);
        var local = new AssemblyCirculationConstraints.Flight(Set.of(new BlockPos(5, 6, 3)), Set.of());
        var shifted = AssemblyCirculationConstraints.shift(local, new BlockPos(10, 2, 20));
        assertEquals(upper, ExteriorCirculationValidator.findCollision(List.of(stone(upper)), List.of(shifted), volumes)
                .orElseThrow().position());
        var voidCell = new BlockPos(18, 4, 20);
        assertTrue(ExteriorCirculationValidator.findCollision(List.of(stone(voidCell)), flight(voidCell, true), volumes).isEmpty());
    }
}
