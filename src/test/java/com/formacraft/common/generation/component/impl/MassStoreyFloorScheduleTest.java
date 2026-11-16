package com.formacraft.common.generation.component.impl;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.util.GeneratedSurfaceCapture;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MassStoreyFloorScheduleTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
    }

    private Map<BlockPos, BlockPatch> generate(String type, int height, Map<String, Object> schedule) {
        var params = new HashMap<String, Object>(schedule);
        params.put("anchor_mode", "min_corner");
        params.put("hollow", true);
        params.put("suppress_windows", true);
        params.put("suppress_doors", true);
        params.put("floor_block", "minecraft:oak_planks");
        params.put("roof_type", "none");
        var mass = new Component(type, "main", new Vec3i(10, 20, 30), new Dimensions(10, 12, height), List.of(), params);
        var result = new HashMap<BlockPos, BlockPatch>();
        for (var patch : new MassMainGenerator().generate(new SemanticComponent(type, null, mass, "DEFAULT")))
            result.put(new BlockPos(patch.dx(), patch.dy(), patch.dz()), patch);
        return result;
    }

    private void assertFloor(Map<BlockPos, BlockPatch> patches, int localY) {
        for (int x = 11; x < 19; x++) for (int z = 31; z < 41; z++) {
            var position = new BlockPos(x, 20 + localY, z);
            assertTrue(GeneratedSurfaceCapture.occupied(patches.get(position)), position.toShortString());
            assertEquals("minecraft:oak_planks", patches.get(position).targetBlock());
        }
    }

    @Test void tallerLobbyUsesTheSameFloorLevelsAsCirculation() {
        var patches = generate("MASS_MAIN", 13, Map.of("floor_count", 3, "floor_height", 4, "ground_clear_height", 5));
        for (int y : List.of(0, 5, 9)) assertFloor(patches, y);
        for (int y : List.of(4, 8)) assertFalse(GeneratedSurfaceCapture.occupied(patches.get(new BlockPos(15, 20 + y, 35))));
    }

    @Test void ordinaryDerivedStoreysStillHaveFullInteriorSlabs() {
        var patches = generate("MASS_MAIN", 16, Map.of("floor_count", 4));
        for (int y : List.of(0, 4, 8, 12)) assertFloor(patches, y);
        assertFalse(GeneratedSurfaceCapture.occupied(patches.get(new BlockPos(15, 25, 35))));
    }

    @Test void hostedSecondaryKeepsOnlyItsOwnDeck() {
        var patches = generate("MASS_SECONDARY", 13,
                Map.of("floor_count", 3, "floor_height", 4, "ground_clear_height", 5, "host_id", "root"));
        assertFloor(patches, 0);
        for (int y : List.of(5, 9)) assertFalse(GeneratedSurfaceCapture.occupied(patches.get(new BlockPos(15, 20 + y, 35))));
    }
}
