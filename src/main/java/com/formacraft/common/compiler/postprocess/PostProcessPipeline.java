package com.formacraft.common.compiler.postprocess;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.FormacraftMod;

import java.util.ArrayList;
import java.util.List;

/**
 * PostProcessPipeline（后处理管道）
 * 
 * 按顺序执行多个后处理器，对 BlockPatch 列表进行后处理。
 * 
 * 执行顺序：
 * 1. DetailRulePostProcessor - 条件式收边规则（线脚/基座带等）
 * 2. WindowOrderPostProcessor - 窗套 sill/lintel
 * 3. DetailEnhancementPostProcessor - 细节装饰增强
 * 4. MaterialVariationPostProcessor - 材质变化
 * 5. TerrainAdaptationPostProcessor - 地形适应（如果提供了 world 和 terrainSampler）
 */
public class PostProcessPipeline {

    private final List<PostProcessor> processors = new ArrayList<>();

    /**
     * 创建默认的后处理管道
     */
    public static PostProcessPipeline createDefault(PostProcessContext context) {
        PostProcessPipeline pipeline = new PostProcessPipeline();

        // 0. Declarative detail rules + floor_cornice/base_plinth presets
        pipeline.add(new DetailRulePostProcessor());

        // 0.5 Window Order（sill / lintel / pediment）
        pipeline.add(new WindowOrderPostProcessor());

        // 1. 细节装饰增强
        pipeline.add(new DetailEnhancementPostProcessor());
        
        // 2. 材质变化
        pipeline.add(new MaterialVariationPostProcessor());
        
        // 3. 地形适应（如果 context 提供了 world 和 terrainSampler）
        // 注意：TerrainAdaptationPostProcessor 需要 world 和 terrainSampler
        // 这里暂时不添加，因为 ComponentPlanCompiler 可能无法访问 world
        
        return pipeline;
    }

    /**
     * 创建包含地形适应的后处理管道
     */
    public static PostProcessPipeline createWithTerrain(
            PostProcessContext context,
            net.minecraft.server.world.ServerWorld world,
            com.formacraft.common.terrain.TerrainStrategySampler terrainSampler
    ) {
        PostProcessPipeline pipeline = createDefault(context);
        
        // 添加地形适应
        if (world != null && terrainSampler != null) {
            pipeline.add(new TerrainAdaptationPostProcessor(world, terrainSampler));
        }
        
        return pipeline;
    }

    /**
     * 添加后处理器
     */
    public PostProcessPipeline add(PostProcessor processor) {
        if (processor != null) {
            processors.add(processor);
        }
        return this;
    }

    /**
     * 执行所有后处理器
     */
    public List<BlockPatch> process(List<BlockPatch> patches, PostProcessContext context) {
        if (patches == null || patches.isEmpty()) {
            return patches;
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
            try (var materials = com.formacraft.common.palette.component.PaletteSelectionScope.open(
                    context.plan(), "postprocess:" + processor.getClass().getName())) {
                List<BlockPatch> previous = new ArrayList<>(result);
                result = processor.process(result, context);
                if (result == null) {
                    FormacraftMod.LOGGER.warn("PostProcessor {} returned null, using previous result", 
                            processor.getClass().getSimpleName());
                    result = patches;
                    break;
                }
                // Terrain deliberately changes coordinates; comparing against its old positions
                // would duplicate the building. Later processors use its translated output.
                if (!(processor instanceof TerrainAdaptationPostProcessor)) {
                    var integrity = ExteriorIntegrityGuard.preserve(previous, result, context);
                    result = integrity.patches();
                    result = preserveMaterials(previous, result, context.protectedMaterials());
                    if (processor instanceof DetailRulePostProcessor || processor instanceof WindowOrderPostProcessor
                            || processor instanceof DetailEnhancementPostProcessor)
                        result = preserveDecorationRestrictions(previous, result, context.decorationRestrictions());
                    if (integrity.restored() > 0) {
                        FormacraftMod.LOGGER.warn("Exterior integrity: processor={} restored={} stage=plan_patches",
                                processor.getClass().getSimpleName(), integrity.restored());
                    }
                }
            } catch (Exception e) {
                FormacraftMod.LOGGER.error("PostProcessor {} failed: {}", 
                        processor.getClass().getSimpleName(), e.getMessage(), e);
                // 继续执行下一个处理器
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

        return result;
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

