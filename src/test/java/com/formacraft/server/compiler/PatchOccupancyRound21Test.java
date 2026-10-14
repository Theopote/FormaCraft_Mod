package com.formacraft.server.compiler;

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

class PatchOccupancyRound21Test {
    @BeforeAll
    static void bootstrap() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void placeOnlyCannotRemoveOrOverwriteExistingMass() {
        PatchOccupancyIndex idx = new PatchOccupancyIndex();
        Component mass = component("MASS_MAIN");
        idx.commit(mass, List.of(new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:stone_bricks")));
        var before = idx.placeAt(new BlockPos(0, 0, 0));

        List<BlockPatch> base = List.of(new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:stone_bricks"));
        List<BlockPatch> carved = List.of(); // folded: cell removed
        var removeDelta = PostProcessPatchDelta.diff(base, carved);
        assertFalse(removeDelta.isEmpty());
        var removeConflict = idx.validatePostProcess(
                "DetailRulePostProcessor", PostProcessWritePolicy.PLACE_ONLY, removeDelta);
        assertTrue(removeConflict.isPresent());
        assertEquals("unauthorized_remove", removeConflict.get().reason());
        assertEquals(before.targetBlock(), idx.placeAt(new BlockPos(0, 0, 0)).targetBlock());
        assertEquals(before.writerType(), idx.placeAt(new BlockPos(0, 0, 0)).writerType());

        List<BlockPatch> overwritten = List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:oak_planks"));
        var overwriteDelta = PostProcessPatchDelta.diff(base, overwritten);
        var overwriteConflict = idx.validatePostProcess(
                "DetailEnhancementPostProcessor", PostProcessWritePolicy.PLACE_ONLY, overwriteDelta);
        assertTrue(overwriteConflict.isPresent());
        assertEquals("unauthorized_place_overwrite", overwriteConflict.get().reason());
        assertEquals("minecraft:stone_bricks", idx.placeAt(new BlockPos(0, 0, 0)).targetBlock());
    }

    @Test
    void carveWithinScopeAllowsRemoveAndCommit() {
        PatchOccupancyIndex idx = new PatchOccupancyIndex();
        Component mass = component("MASS_MAIN");
        idx.commit(mass, List.of(new BlockPatch(BlockPatch.PLACE, 2, 0, 2, "minecraft:stone")));

        List<BlockPatch> before = List.of(new BlockPatch(BlockPatch.PLACE, 2, 0, 2, "minecraft:stone"));
        List<BlockPatch> after = List.of();
        var delta = PostProcessPatchDelta.diff(before, after);
        assertTrue(idx.acceptPostProcess(
                "TerrainAdaptationPostProcessor",
                PostProcessWritePolicy.CARVE_WITHIN_SCOPE,
                delta).isEmpty());
        assertNull(idx.placeAt(new BlockPos(2, 0, 2)));
    }

    @Test
    void decorateThenMaterialVariationReplaceAllowed() {
        PatchOccupancyIndex idx = new PatchOccupancyIndex();
        Component mass = component("MASS_MAIN");
        idx.commit(mass, List.of(new BlockPatch(BlockPatch.PLACE, 1, 1, 1, "minecraft:stone")));

        // PLACE_ONLY decor adds a new cell
        List<BlockPatch> beforeDecor = List.of(
                new BlockPatch(BlockPatch.PLACE, 1, 1, 1, "minecraft:stone"));
        List<BlockPatch> afterDecor = List.of(
                new BlockPatch(BlockPatch.PLACE, 1, 1, 1, "minecraft:stone"),
                new BlockPatch(BlockPatch.PLACE, 3, 3, 3, "minecraft:oak_stairs"));
        var decorDelta = PostProcessPatchDelta.diff(beforeDecor, afterDecor);
        assertTrue(idx.acceptPostProcess(
                "DetailRulePostProcessor", PostProcessWritePolicy.PLACE_ONLY, decorDelta).isEmpty());
        assertEquals("POSTPROCESS:DETAILRULEPOSTPROCESSOR",
                idx.placeAt(new BlockPos(3, 3, 3)).writerType());

        // REPLACE_WITHIN_SCOPE swaps material on that cell
        List<BlockPatch> afterVar = List.of(
                new BlockPatch(BlockPatch.PLACE, 1, 1, 1, "minecraft:stone"),
                new BlockPatch(BlockPatch.PLACE, 3, 3, 3, "minecraft:spruce_stairs"));
        var varDelta = PostProcessPatchDelta.diff(afterDecor, afterVar);
        assertTrue(idx.acceptPostProcess(
                "MaterialVariationPostProcessor",
                PostProcessWritePolicy.REPLACE_WITHIN_SCOPE,
                varDelta).isEmpty());
        var afterVarPlace = idx.placeAt(new BlockPos(3, 3, 3));
        assertNotNull(afterVarPlace);
        assertTrue(afterVarPlace.targetBlock().contains("spruce_stairs"),
                afterVarPlace.targetBlock());
        assertEquals("POSTPROCESS:MATERIALVARIATIONPOSTPROCESSOR", afterVarPlace.writerType());
    }

    @Test
    void validateFailureLeavesIndexUnchanged() {
        PatchOccupancyIndex idx = new PatchOccupancyIndex();
        Component mass = component("MASS_MAIN");
        idx.commit(mass, List.of(new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:cobblestone")));

        List<BlockPatch> before = List.of(new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:cobblestone"));
        List<BlockPatch> after = List.of(new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:dirt"));
        var delta = PostProcessPatchDelta.diff(before, after);

        assertTrue(idx.validatePostProcess("DetailRulePostProcessor",
                PostProcessWritePolicy.PLACE_ONLY, delta).isPresent());
        // Must not call commit after failed validate — index still MASS
        assertEquals("MASS_MAIN", idx.placeAt(new BlockPos(0, 0, 0)).writerType());
        assertEquals("minecraft:cobblestone", idx.placeAt(new BlockPos(0, 0, 0)).targetBlock());
    }

    private static Component component(String type) {
        return new Component(type, null, new Vec3i(0, 0, 0), new Dimensions(1, 1, 1), List.of(), Map.of());
    }
}
