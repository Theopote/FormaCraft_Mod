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
 * 1. DetailEnhancementPostProcessor - 细节装饰增强
 * 2. MaterialVariationPostProcessor - 材质变化
 * 3. TerrainAdaptationPostProcessor - 地形适应（如果提供了 world 和 terrainSampler）
 */
public class PostProcessPipeline {

    private final List<PostProcessor> processors = new ArrayList<>();

    /**
     * 创建默认的后处理管道
     */
    public static PostProcessPipeline createDefault(PostProcessContext context) {
        PostProcessPipeline pipeline = new PostProcessPipeline();
        
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
        
        for (PostProcessor processor : processors) {
            try {
                result = processor.process(result, context);
                if (result == null) {
                    FormacraftMod.LOGGER.warn("PostProcessor {} returned null, using previous result", 
                            processor.getClass().getSimpleName());
                    result = patches;
                    break;
                }
            } catch (Exception e) {
                FormacraftMod.LOGGER.error("PostProcessor {} failed: {}", 
                        processor.getClass().getSimpleName(), e.getMessage(), e);
                // 继续执行下一个处理器
            }
        }

        FormacraftMod.LOGGER.debug("PostProcessPipeline: processed {} patches through {} processors", 
                result.size(), processors.size());
        
        return result;
    }
}

