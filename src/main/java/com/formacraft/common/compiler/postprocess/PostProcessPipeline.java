package com.formacraft.common.compiler.postprocess;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.FormacraftMod;

import java.util.ArrayList;
import java.util.List;

/**
 * PostProcessPipeline（后处理管道）
 *
 * 按顺序执行多个后处理器。SOFT 失败保留上一阶段；HARD 失败中止并标记 fatal。
 */
public class PostProcessPipeline {

    private final List<PostProcessor> processors = new ArrayList<>();

    public record ProcessOutcome(List<BlockPatch> patches, boolean hardFailure, String failedProcessor, String errorMessage) {
        public static ProcessOutcome ok(List<BlockPatch> patches) {
            return new ProcessOutcome(patches, false, null, null);
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

        List<BlockPatch> result = patches;
        var reserved = new java.util.LinkedHashMap<net.minecraft.util.math.BlockPos, BlockPatch>();
        for (var patch : patches) {
            var pos = new net.minecraft.util.math.BlockPos(patch.dx(), patch.dy(), patch.dz());
            if (context.protectedClearance().contains(pos)) reserved.put(pos, patch);
        }

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
                        return new ProcessOutcome(List.copyOf(previous), true, name, "processor returned null");
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
            } catch (Exception e) {
                result = previous; // always roll back shared/partial state
                if (processor.failurePolicy() == PostProcessor.FailurePolicy.HARD) {
                    FormacraftMod.LOGGER.error("PostProcessor {} failed (HARD): {}", name, e.getMessage(), e);
                    return new ProcessOutcome(List.copyOf(previous), true, name, e.getMessage());
                }
                FormacraftMod.LOGGER.warn("PostProcessor {} failed (SOFT), keeping previous: {}",
                        name, e.getMessage(), e);
            }
        }

        if (!reserved.isEmpty()) {
            var preserved = new ArrayList<BlockPatch>();
            if (result != null) {
                for (var patch : result) {
                    var pos = new net.minecraft.util.math.BlockPos(patch.dx(), patch.dy(), patch.dz());
                    if (!context.protectedClearance().contains(pos)) preserved.add(patch);
                }
            }
            preserved.addAll(reserved.values());
            result = preserved;
        }
        if (result != null) {
            FormacraftMod.LOGGER.debug("PostProcessPipeline: processed {} patches through {} processors",
                    result.size(), processors.size());
        }

        return ProcessOutcome.ok(result);
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
