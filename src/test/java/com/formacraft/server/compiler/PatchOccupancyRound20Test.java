package com.formacraft.server.compiler;

import com.formacraft.common.component.group.PlayerComponentGroupExpander;
import com.formacraft.common.compiler.postprocess.PostProcessPatchDelta;
import com.formacraft.common.compiler.postprocess.PostProcessWritePolicy;
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

class PatchOccupancyRound20Test {
    @BeforeAll
    static void bootstrap() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void overwritePrivilegeDoesNotInheritViaSameMaterial() {
        PatchOccupancyIndex idx = new PatchOccupancyIndex();
        Component door = component("DOOR");
        Component decor = component("DECOR_DETAIL");
        idx.commit(door, List.of(new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:stone_bricks")));
        // Same material rewrite by decor — ownership becomes DECOR without carve/overwrite power
        assertTrue(idx.accept(decor, List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:stone_bricks"))).isEmpty());
        assertEquals("DECOR_DETAIL", idx.placeAt(new BlockPos(0, 0, 0)).writerType());
        // Decor cannot then overwrite with different material
        assertTrue(idx.validate(decor, List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:oak_planks"))).isPresent());
        assertEquals("unauthorized_place_overwrite",
                idx.validate(decor, List.of(
                        new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:oak_planks"))).get().reason());
    }

    @Test
    void unauthorizedRemoveFailsAuthorizedDoorSucceeds() {
        PatchOccupancyIndex idx = new PatchOccupancyIndex();
        Component mass = component("MASS_MAIN");
        Component decor = component("DECOR_DETAIL");
        Component door = component("DOOR");
        idx.commit(mass, List.of(new BlockPatch(BlockPatch.PLACE, 1, 0, 1, "minecraft:stone_bricks")));
        var bad = idx.validate(decor, List.of(
                new BlockPatch(BlockPatch.REMOVE, 1, 0, 1, "minecraft:air")));
        assertTrue(bad.isPresent());
        assertEquals("unauthorized_remove", bad.get().reason());
        assertEquals("minecraft:stone_bricks", idx.placeAt(new BlockPos(1, 0, 1)).targetBlock());

        assertTrue(idx.accept(door, List.of(
                new BlockPatch(BlockPatch.REMOVE, 1, 0, 1, "minecraft:air"),
                new BlockPatch(BlockPatch.PLACE, 1, 0, 1, "minecraft:oak_door"))).isEmpty());
        assertEquals("DOOR", idx.placeAt(new BlockPos(1, 0, 1)).writerType());
    }

    @Test
    void postProcessDeltaCommitTracksNewCells() {
        PatchOccupancyIndex idx = new PatchOccupancyIndex();
        Component mass = component("MASS_MAIN");
        idx.commit(mass, List.of(new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:stone")));

        List<BlockPatch> before = List.of(new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:stone"));
        List<BlockPatch> after = List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:stone"),
                new BlockPatch(BlockPatch.PLACE, 5, 5, 5, "minecraft:oak_stairs")
        );
        var delta = PostProcessPatchDelta.diff(before, after);
        assertFalse(delta.addedPlaces().isEmpty());
        assertTrue(idx.acceptPostProcess("trim", PostProcessWritePolicy.PLACE_ONLY, delta).isEmpty());

        assertTrue(PatchConflictValidator.inspectAuthorized(after, idx).isEmpty());
    }

    @Test
    void untrackedFinalPlaceIsConflict() {
        PatchOccupancyIndex idx = new PatchOccupancyIndex();
        Component mass = component("MASS_MAIN");
        idx.commit(mass, List.of(new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:stone")));
        List<BlockPatch> sneaky = List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:stone"),
                new BlockPatch(BlockPatch.PLACE, 9, 9, 9, "minecraft:dirt")
        );
        var finding = PatchConflictValidator.inspectAuthorized(sneaky, idx);
        assertTrue(finding.isPresent());
        assertEquals("untracked_place", finding.get().reason());
    }

    @Test
    void mountRequiredDefaultsTrue() {
        assertTrue(PlayerComponentGroupExpander.isMountRequiredParams(null));
        assertTrue(PlayerComponentGroupExpander.isMountRequiredParams(Map.of()));
        assertFalse(PlayerComponentGroupExpander.isMountRequiredParams(Map.of("mount_required", false)));
        assertFalse(PlayerComponentGroupExpander.isMountRequiredParams(Map.of("required", "false")));
        assertTrue(PlayerComponentGroupExpander.isMountRequiredParams(Map.of("mount_required", true)));
    }

    private static Component component(String type) {
        return new Component(type, null, new Vec3i(0, 0, 0), new Dimensions(1, 1, 1), List.of(), Map.of());
    }
}
