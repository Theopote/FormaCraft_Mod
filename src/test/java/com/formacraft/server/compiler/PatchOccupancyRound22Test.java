package com.formacraft.server.compiler;

import com.formacraft.common.compiler.postprocess.PostProcessContext;
import com.formacraft.common.compiler.postprocess.PostProcessPatchDelta;
import com.formacraft.common.compiler.postprocess.PostProcessPipeline;
import com.formacraft.common.compiler.postprocess.PostProcessWritePolicy;
import com.formacraft.common.compiler.postprocess.PostProcessWriteScope;
import com.formacraft.common.compiler.postprocess.ProtectedClearanceRestorer;
import com.formacraft.common.generation.component.util.ComponentFootprintUtil;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PatchOccupancyRound22Test {
    @BeforeAll
    static void bootstrap() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void carveOutsideSeedAndVolumeIsRejected() {
        PatchOccupancyIndex idx = new PatchOccupancyIndex();
        Component mass = component("MASS_MAIN");
        idx.commit(mass, List.of(new BlockPatch(BlockPatch.PLACE, 9, 0, 9, "minecraft:stone")));

        List<BlockPatch> before = List.of(new BlockPatch(BlockPatch.PLACE, 9, 0, 9, "minecraft:stone"));
        List<BlockPatch> after = List.of();
        var delta = PostProcessPatchDelta.diff(before, after);

        // Volume covers 0..4 only; seed is empty — (9,0,9) out of scope
        var volume = new PostProcessContext.BuildingVolume(
                "main",
                new ComponentFootprintUtil.Bounds(0, 0, 0, 4, 4, 4),
                4);
        PostProcessContext ctx = new PostProcessContext(
                emptyPlan(), new BlockPos(0, 0, 0), new Vec3i(0, 0, 0),
                List.of(volume), Set.of(), Set.of());
        PostProcessWriteScope scope = PostProcessWriteScope.fromContext(ctx, Set.of());

        var conflict = idx.validatePostProcess(
                "TerrainAdaptationPostProcessor",
                PostProcessWritePolicy.CARVE_WITHIN_SCOPE,
                delta,
                scope);
        assertTrue(conflict.isPresent());
        assertEquals("out_of_scope_remove", conflict.get().reason());
        assertEquals("minecraft:stone", idx.placeAt(new BlockPos(9, 0, 9)).targetBlock());
    }

    @Test
    void carveInsideSeedAllowed() {
        PatchOccupancyIndex idx = new PatchOccupancyIndex();
        Component mass = component("MASS_MAIN");
        idx.commit(mass, List.of(new BlockPatch(BlockPatch.PLACE, 2, 0, 2, "minecraft:stone")));

        List<BlockPatch> before = List.of(new BlockPatch(BlockPatch.PLACE, 2, 0, 2, "minecraft:stone"));
        List<BlockPatch> after = List.of();
        var delta = PostProcessPatchDelta.diff(before, after);
        PostProcessWriteScope scope = PostProcessWriteScope.fromContext(
                new PostProcessContext(emptyPlan(), new BlockPos(0, 0, 0), new Vec3i(0, 0, 0)),
                Set.of(new BlockPos(2, 0, 2)));

        assertTrue(idx.acceptPostProcess(
                "TerrainAdaptationPostProcessor",
                PostProcessWritePolicy.CARVE_WITHIN_SCOPE,
                delta,
                scope).isEmpty());
        assertNull(idx.placeAt(new BlockPos(2, 0, 2)));
    }

    @Test
    void clearanceRestorerEmitsAuditableDelta() {
        BlockPos clear = new BlockPos(1, 0, 1);
        List<BlockPatch> initial = List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:stone"),
                new BlockPatch(BlockPatch.PLACE, 1, 0, 1, "minecraft:air")
        );
        var reserved = ProtectedClearanceRestorer.snapshotReserved(initial, Set.of(clear));
        assertEquals(1, reserved.size());

        // Processor overwrote clearance cell
        List<BlockPatch> afterProc = List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:stone"),
                new BlockPatch(BlockPatch.PLACE, 1, 0, 1, "minecraft:oak_planks")
        );
        var restored = ProtectedClearanceRestorer.apply(afterProc, reserved, Set.of(clear));
        assertFalse(restored.delta().isEmpty());
        assertTrue(restored.scope().allowsPlace(clear));

        PatchOccupancyIndex idx = new PatchOccupancyIndex();
        idx.commit(component("MASS_MAIN"), List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:stone")));
        // Simulate processor commit of overwrite
        idx.acceptPostProcess("DetailRulePostProcessor", PostProcessWritePolicy.PLACE_ONLY,
                PostProcessPatchDelta.diff(
                        List.of(new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:stone")),
                        afterProc),
                PostProcessWriteScope.unbounded());
        assertTrue(idx.acceptPostProcess(
                ProtectedClearanceRestorer.PROCESSOR_ID,
                PostProcessWritePolicy.CARVE_WITHIN_SCOPE,
                restored.delta(),
                restored.scope()).isEmpty());
        assertTrue(PatchConflictValidator.inspectAuthorized(restored.patches(), idx).isEmpty());
    }

    @Test
    void pipelineRestorerAppearsInProcessorDeltas() {
        BlockPos clear = new BlockPos(2, 0, 2);
        LlmPlan plan = emptyPlan();
        PostProcessContext ctx = new PostProcessContext(
                plan, new BlockPos(0, 64, 0), new Vec3i(0, 0, 0),
                List.of(), Set.of(clear), Set.of());
        List<BlockPatch> input = List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:stone"),
                new BlockPatch(BlockPatch.PLACE, 2, 0, 2, "minecraft:air")
        );
        PostProcessPipeline pipeline = new PostProcessPipeline();
        pipeline.add((patches, context) -> {
            var out = new java.util.ArrayList<>(patches);
            out.removeIf(p -> p.dx() == 2 && p.dy() == 0 && p.dz() == 2);
            out.add(new BlockPatch(BlockPatch.PLACE, 2, 0, 2, "minecraft:dirt"));
            return out;
        });
        var outcome = pipeline.processWithOutcome(input, ctx);
        assertFalse(outcome.hardFailure());
        assertTrue(outcome.processorDeltas().stream()
                .anyMatch(d -> ProtectedClearanceRestorer.PROCESSOR_ID.equals(d.processorId())));
        boolean clearanceRestored = outcome.patches().stream()
                .anyMatch(p -> p.dx() == 2 && p.dz() == 2 && "minecraft:air".equals(p.targetBlock()));
        assertTrue(clearanceRestored);
    }

    private static Component component(String type) {
        return new Component(type, null, new Vec3i(0, 0, 0), new Dimensions(1, 1, 1), List.of(), Map.of());
    }

    private static LlmPlan emptyPlan() {
        return LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).components(List.of()).build();
    }
}
