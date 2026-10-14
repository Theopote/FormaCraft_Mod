package com.formacraft.common.compiler.postprocess;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.FormacraftMod;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * PostProcessPipeline（后处理管道）
 *
 * 按顺序执行多个后处理器。SOFT 失败保留上一阶段；HARD 失败中止并标记 fatal。
 * 每个成功的处理器产出独立 {@link ProcessorDelta}；净空恢复为可审计末段。
 */
public class PostProcessPipeline {

    private final List<PostProcessor> processors = new ArrayList<>();

    public record ProcessorDelta(
            String processorId,
            PostProcessPatchDelta delta,
            PostProcessWritePolicy policy,
            PostProcessWriteScope scope
    ) {
        public ProcessorDelta(String processorId, PostProcessPatchDelta delta, PostProcessWritePolicy policy) {
            this(processorId, delta, policy, PostProcessWriteScope.unbounded());
        }
    }

    public record ProcessOutcome(
            List<BlockPatch> patches,
            boolean hardFailure,
            String failedProcessor,
            String errorMessage,
            List<ProcessorDelta> processorDeltas
    ) {
        public static ProcessOutcome ok(List<BlockPatch> patches) {
            return ok(patches, List.of());
        }

        public static ProcessOutcome ok(List<BlockPatch> patches, List<ProcessorDelta> deltas) {
            return new ProcessOutcome(patches, false, null, null,
                    deltas == null ? List.of() : List.copyOf(deltas));
        }
    }

    public static PostProcessPipeline createDefault(PostProcessContext context) {
        PostProcessPipeline pipeline = new PostProcessPipeline();
        pipeline.add(new DetailRulePostProcessor());
        pipeline.add(new WindowOrderPostProcessor());
        pipeline.add(new DetailEnhancementPostProcessor());
        pipeline.add(new MaterialVariationPostProcessor());
        return pipeline;
    }

    public static PostProcessPipeline createWithTerrain(
            PostProcessContext context,
            net.minecraft.server.world.ServerWorld world,
            com.formacraft.common.terrain.TerrainStrategySampler terrainSampler
    ) {
        PostProcessPipeline pipeline = createDefault(context);
        if (world != null && terrainSampler != null) {
            pipeline.add(new TerrainAdaptationPostProcessor(world, terrainSampler));
        }
        return pipeline;
    }

    public PostProcessPipeline add(PostProcessor processor) {
        if (processor != null) {
            processors.add(processor);
        }
        return this;
    }

    /** Backward-compatible: returns patches only (empty on hard failure). Prefer {@link #processWithOutcome}. */
    public List<BlockPatch> process(List<BlockPatch> patches, PostProcessContext context) {
        ProcessOutcome outcome = processWithOutcome(patches, context);
        if (outcome.hardFailure()) {
            return List.of();
        }
        return outcome.patches();
    }

    public ProcessOutcome processWithOutcome(List<BlockPatch> patches, PostProcessContext context) {
        if (patches == null || patches.isEmpty()) {
            return ProcessOutcome.ok(patches);
        }

        // Defensive copy — processors must not mutate the caller-owned list.
        List<BlockPatch> result = new ArrayList<>(patches);
        List<ProcessorDelta> deltas = new ArrayList<>();
        var reserved = ProtectedClearanceRestorer.snapshotReserved(patches, context.protectedClearance());

        for (PostProcessor processor : processors) {
            if (com.formacraft.common.style.ExplicitDesignPolicy.noComplexDecor(context.plan(), null)
                    && (processor instanceof DetailRulePostProcessor || processor instanceof WindowOrderPostProcessor
                    || processor instanceof DetailEnhancementPostProcessor)) continue;
            List<BlockPatch> previous = new ArrayList<>(result);
            String name = processor.getClass().getSimpleName();
            try (var materials = com.formacraft.common.palette.component.PaletteSelectionScope.open(
                    context.plan(), "postprocess:" + processor.getClass().getName())) {
                result = processor.process(result, context);
                if (result == null) {
                    if (processor.failurePolicy() == PostProcessor.FailurePolicy.HARD) {
                        FormacraftMod.LOGGER.error("PostProcessor {} returned null (HARD)", name);
                        return new ProcessOutcome(List.copyOf(previous), true, name, "processor returned null",
                                List.copyOf(deltas));
                    }
                    FormacraftMod.LOGGER.warn("PostProcessor {} returned null, using previous result", name);
                    result = previous;
                    continue;
                }
                if (!(processor instanceof TerrainAdaptationPostProcessor)) {
                    var integrity = ExteriorIntegrityGuard.preserve(previous, result, context);
                    result = integrity.patches();
                    result = preserveMaterials(previous, result, context.protectedMaterials());
                    if (processor instanceof DetailRulePostProcessor || processor instanceof WindowOrderPostProcessor
                            || processor instanceof DetailEnhancementPostProcessor)
                        result = preserveDecorationRestrictions(previous, result, context.decorationRestrictions());
                    if (integrity.restored() > 0) {
                        FormacraftMod.LOGGER.warn("Exterior integrity: processor={} restored={} stage=plan_patches",
                                name, integrity.restored());
                    }
                }
                PostProcessPatchDelta stepDelta = PostProcessPatchDelta.diff(previous, result);
                if (!stepDelta.isEmpty()) {
                    Set<BlockPos> seeds = new HashSet<>(PostProcessPatchDelta.foldPlaces(previous).keySet());
                    PostProcessWriteScope scope = PostProcessWriteScope.fromContext(context, seeds);
                    deltas.add(new ProcessorDelta(name, stepDelta, processor.writePolicy(), scope));
                }
            } catch (Exception e) {
                result = previous; // always roll back shared/partial state
                if (processor.failurePolicy() == PostProcessor.FailurePolicy.HARD) {
                    FormacraftMod.LOGGER.error("PostProcessor {} failed (HARD): {}", name, e.getMessage(), e);
                    return new ProcessOutcome(List.copyOf(previous), true, name, e.getMessage(),
                            List.copyOf(deltas));
                }
                FormacraftMod.LOGGER.warn("PostProcessor {} failed (SOFT), keeping previous: {}",
                        name, e.getMessage(), e);
            }
        }

        if (!reserved.isEmpty()) {
            List<BlockPatch> beforeRestore = result;
            var restored = ProtectedClearanceRestorer.apply(result, reserved, context.protectedClearance());
            result = restored.patches();
            if (!restored.delta().isEmpty()) {
                deltas.add(new ProcessorDelta(
                        ProtectedClearanceRestorer.PROCESSOR_ID,
                        restored.delta(),
                        PostProcessWritePolicy.CARVE_WITHIN_SCOPE,
                        restored.scope()));
                FormacraftMod.LOGGER.debug("ProtectedClearanceRestorer: delta non-empty beforeSize={} afterSize={}",
                        beforeRestore == null ? 0 : beforeRestore.size(),
                        result == null ? 0 : result.size());
            }
        }
        if (result != null) {
            FormacraftMod.LOGGER.debug("PostProcessPipeline: processed {} patches through {} processors",
                    result.size(), processors.size());
        }

        return ProcessOutcome.ok(result, deltas);
    }

    private static List<BlockPatch> preserveMaterials(List<BlockPatch> before, List<BlockPatch> after,
                                                       java.util.Set<net.minecraft.util.math.BlockPos> locked) {
        if (locked.isEmpty()) return after;
        var originals = new java.util.LinkedHashMap<net.minecraft.util.math.BlockPos, BlockPatch>();
        for (var patch : before) {
            var pos = new net.minecraft.util.math.BlockPos(patch.dx(), patch.dy(), patch.dz());
            if (locked.contains(pos)) originals.put(pos, patch);
        }
        var result = new ArrayList<BlockPatch>();
        for (var patch : after) {
            var pos = new net.minecraft.util.math.BlockPos(patch.dx(), patch.dy(), patch.dz());
            if (!originals.containsKey(pos)) result.add(patch);
        }
        result.addAll(originals.values());
        return result;
    }

    private static List<BlockPatch> preserveDecorationRestrictions(List<BlockPatch> before, List<BlockPatch> after,
                                                                   java.util.Set<net.minecraft.util.math.BlockPos> restricted) {
        if (restricted.isEmpty()) return after;
        var original = new java.util.LinkedHashMap<net.minecraft.util.math.BlockPos, BlockPatch>();
        for (var patch : before) {
            var pos = new net.minecraft.util.math.BlockPos(patch.dx(), patch.dy(), patch.dz());
            if (restricted.contains(pos)) original.put(pos, patch);
        }
        var result = new ArrayList<BlockPatch>();
        for (var patch : after) {
            var pos = new net.minecraft.util.math.BlockPos(patch.dx(), patch.dy(), patch.dz());
            if (!restricted.contains(pos)) result.add(patch);
        }
        result.addAll(original.values());
        return result;
    }
}
