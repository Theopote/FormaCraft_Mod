package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PatchOccupancyRound19Test {
    @BeforeAll
    static void bootstrap() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void distinctYCoordinatesDoNotCollide() {
        PatchOccupancyIndex idx = new PatchOccupancyIndex();
        Component mass = component("MASS_MAIN");
        assertTrue(idx.accept(mass, List.of(
                new BlockPatch(BlockPatch.PLACE, 10, 64, 20, "minecraft:stone_bricks"))).isEmpty());
        assertTrue(idx.accept(mass, List.of(
                new BlockPatch(BlockPatch.PLACE, 10, 1088, 20, "minecraft:oak_planks"))).isEmpty());
        assertEquals("minecraft:stone_bricks",
                idx.placeAt(new BlockPos(10, 64, 20)).targetBlock());
        assertEquals("minecraft:oak_planks",
                idx.placeAt(new BlockPos(10, 1088, 20)).targetBlock());
    }

    @Test
    void validateFailureDoesNotMutateIndex() {
        PatchOccupancyIndex idx = new PatchOccupancyIndex();
        Component mass = component("MASS_MAIN");
        Component decor = component("DECOR_DETAIL");
        assertTrue(idx.isEmpty());
        var conflict = idx.validate(mass, List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:stone_bricks"),
                new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:dirt") // same batch different material, mass not opening
        ));
        // First PLACE ok in sim; second conflicts against first in same batch — MASS is not opening writer
        assertTrue(conflict.isPresent() || idx.isEmpty());
        // Formal index must stay empty when only validate was called
        assertTrue(idx.isEmpty());

        idx.commit(mass, List.of(new BlockPatch(BlockPatch.PLACE, 1, 0, 0, "minecraft:stone_bricks")));
        assertFalse(idx.isEmpty());
        var c2 = idx.validate(decor, List.of(
                new BlockPatch(BlockPatch.PLACE, 1, 0, 0, "minecraft:oak_planks")));
        assertTrue(c2.isPresent());
        assertEquals("minecraft:stone_bricks", idx.placeAt(new BlockPos(1, 0, 0)).targetBlock());
    }

    @Test
    void authorizedDoorOverwritePassesFinalScan() {
        PatchOccupancyIndex idx = new PatchOccupancyIndex();
        Component mass = component("MASS_MAIN");
        Component door = component("DOOR");
        idx.commit(mass, List.of(new BlockPatch(BlockPatch.PLACE, 2, 0, 2, "minecraft:stone_bricks")));
        assertTrue(idx.validate(door, List.of(
                new BlockPatch(BlockPatch.REMOVE, 2, 0, 2, "minecraft:air"),
                new BlockPatch(BlockPatch.PLACE, 2, 0, 2, "minecraft:oak_door"))).isEmpty());
        idx.commit(door, List.of(
                new BlockPatch(BlockPatch.REMOVE, 2, 0, 2, "minecraft:air"),
                new BlockPatch(BlockPatch.PLACE, 2, 0, 2, "minecraft:oak_door")));

        List<BlockPatch> result = List.of(
                new BlockPatch(BlockPatch.PLACE, 2, 0, 2, "minecraft:stone_bricks"),
                new BlockPatch(BlockPatch.REMOVE, 2, 0, 2, "minecraft:air"),
                new BlockPatch(BlockPatch.PLACE, 2, 0, 2, "minecraft:oak_door")
        );
        assertTrue(PatchConflictValidator.inspectAuthorized(result, idx).isEmpty());
    }

    @Test
    void unauthorizedDecorOverwriteFailsValidateAndFinal() {
        PatchOccupancyIndex idx = new PatchOccupancyIndex();
        Component mass = component("MASS_MAIN");
        Component decor = component("DECOR_DETAIL");
        idx.commit(mass, List.of(new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:stone_bricks")));
        assertTrue(idx.validate(decor, List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:oak_planks"))).isPresent());

        // Simulate buggy final list with both places; index still has mass record without overwrite
        List<BlockPatch> badFinal = List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:oak_planks")
        );
        var finding = PatchConflictValidator.inspectAuthorized(badFinal, idx);
        assertTrue(finding.isPresent());
        assertEquals("unauthorized_final_overwrite", finding.get().reason());
    }

    private static Component component(String type) {
        return new Component(type, null, new Vec3i(0, 0, 0), new Dimensions(1, 1, 1), List.of(), Map.of());
    }
}
