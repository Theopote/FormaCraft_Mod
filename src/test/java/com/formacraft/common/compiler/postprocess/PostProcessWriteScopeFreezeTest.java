package com.formacraft.common.compiler.postprocess;

import com.formacraft.common.generation.component.util.ComponentFootprintUtil;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.StyleAttributes;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 01.3 / P1-04: post-process write scope is frozen from pipeline-entry geometry.
 * Later PLACE décor must not become the next processor's expansion seed.
 */
class PostProcessWriteScopeFreezeTest {

    @BeforeAll
    static void bootstrap() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void forStepPrefersFrozenAuthorizedScopeOverStepSeeds() {
        var volume = new PostProcessContext.BuildingVolume(
                "m0", new ComponentFootprintUtil.Bounds(0, 0, 0, 6, 6, 6), 4);
        PostProcessContext base = PostProcessContext.create(
                LlmPlanTestFixtures.builder().allowCompilerSatellites(true).build(),
                BlockPos.ORIGIN, List.of(volume));
        PostProcessWriteScope frozen = PostProcessWriteScope.fromContext(base, Set.of(new BlockPos(1, 1, 1)));
        PostProcessContext auth = base.withAuthorizedWriteScope(frozen);

        // Step seeds far outside the frozen seed neighborhood must not expand authority.
        PostProcessWriteScope step = PostProcessWriteScope.forStep(auth, Set.of(new BlockPos(100, 100, 100)));
        assertSame(frozen, step);
        assertTrue(step.allowsPlace(new BlockPos(1, 1, 1)));
        assertFalse(step.allowsPlace(new BlockPos(100, 100, 100)),
                "frozen scope must ignore step-seed ratchet at far cells");
    }

    @Test
    void pipelineDeltasShareIdenticalFrozenScopeAcrossProcessors() {
        // Seed-only context (no volumes): pad is Chebyshev=1 around entry seeds only.
        List<BlockPatch> input = new ArrayList<>();
        input.add(new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:stone_bricks"));
        input.add(new BlockPatch(BlockPatch.PLACE, 1, 0, 0, "minecraft:stone_bricks"));
        input.add(new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:stone_bricks"));
        input.add(new BlockPatch(BlockPatch.PLACE, 1, 1, 0, "minecraft:glass"));

        var attrs = new StyleAttributes(null, null, null, null, null, null, null, null,
                null, null, null, null, null, "deep", "classic", "subtle", "warm_lantern", "high");
        PostProcessContext ctx = PostProcessContext.create(
                LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                        .styleProfile("GOTHIC").styleAttributes(attrs).build(),
                BlockPos.ORIGIN, List.of());

        PostProcessPipeline.ProcessOutcome outcome = PostProcessPipeline.createDefault(ctx)
                .processWithOutcome(input, ctx);
        assertFalse(outcome.hardFailure());
        assertFalse(outcome.processorDeltas().isEmpty(), "expected at least one processor delta");

        PostProcessWriteScope first = outcome.processorDeltas().getFirst().scope();
        assertNotNull(first);
        for (var step : outcome.processorDeltas()) {
            assertSame(first, step.scope(),
                    "every processor delta must reuse the same frozen entry scope, got "
                            + step.processorId());
        }

        // Entry seeds authorize pad around (0,0,0)/(1,0,0)/(0,1,0)/(1,1,0) — not far cells.
        assertTrue(first.allowsPlace(new BlockPos(0, 0, 0)));
        assertTrue(first.allowsPlace(new BlockPos(2, 0, 0))); // pad=1
        assertFalse(first.allowsPlace(new BlockPos(5, 0, 0)),
                "frozen scope must not include far cells from décor expansion");
    }

    @Test
    void laterProcessorCannotAuthorizeCellsOutsideEntryPad() {
        // Tiny authored footprint. Inject a fake early processor that places far décor;
        // lighting/detail still clip to entry-frozen scope.
        List<BlockPatch> input = List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:stone_bricks"),
                new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:stone_bricks"));

        PostProcessContext ctx = PostProcessContext.create(
                LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                        .styleProfile("DEFAULT").build(),
                BlockPos.ORIGIN, List.of());

        PostProcessPipeline pipeline = new PostProcessPipeline();
        pipeline.add((patches, context) -> {
            List<BlockPatch> out = new ArrayList<>(patches);
            // Simulate décor that would ratchet seeds if scope were rebuilt from previous.
            out.add(new BlockPatch(BlockPatch.PLACE, 20, 0, 0, "minecraft:oak_log"));
            return out;
        });
        pipeline.add(new LightingPostProcessor());

        // Freeze explicitly so Lighting sees authorized scope even if Lighting builds step seeds
        // from the expanded list after the fake processor.
        Set<BlockPos> entrySeeds = new HashSet<>(PostProcessPatchDelta.foldPlaces(input).keySet());
        PostProcessWriteScope frozen = PostProcessWriteScope.fromContext(ctx, entrySeeds);
        PostProcessContext auth = ctx.withAuthorizedWriteScope(frozen);

        List<BlockPatch> out = pipeline.process(input, auth);
        assertTrue(out.stream().anyMatch(p -> p.dx() == 20),
                "fake early processor still emits far décor (unscoped injector)");
        // Lighting must not place around the far oak_log using it as a new seed neighborhood.
        assertTrue(out.stream().noneMatch(p -> p != null && p.dx() >= 19 && p.dx() <= 21
                        && p.targetBlock() != null
                        && (p.targetBlock().contains("lantern")
                        || p.targetBlock().contains("torch")
                        || p.targetBlock().contains("end_rod"))),
                "lighting must not expand around post-entry décor seeds");
        assertFalse(frozen.allowsPlace(new BlockPos(20, 0, 0)),
                "entry-frozen seed-only scope must reject far décor cell");
    }
}
