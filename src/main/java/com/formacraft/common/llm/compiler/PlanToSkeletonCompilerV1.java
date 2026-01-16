package com.formacraft.common.llm.compiler;

import com.formacraft.common.llm.compiler.steps.*;
import com.formacraft.common.llm.compiler.steps.impl.*;
import com.formacraft.common.llm.dto.PlanSkeleton;
import com.formacraft.common.llm.dto.StructuralSkeleton;
import com.formacraft.server.skeleton.gen.ExecutableSkeletonPlan;

import java.util.List;

/**
 * PlanToSkeletonCompilerV1（默认实现）
 * <p>
 * 采用管线式结构，非常清晰、可维护、适合长期演进。
 * <p>
 * 内部步骤：
 * 1. PlanNormalizationStep（规范化）
 * 2. StructuralExtractionStep（结构提取）
 * 3. SkeletonGenerationStep（骨架生成）
 * 4. SkeletonPostProcessStep（后处理）
 * 5. SkeletonGraphBuilder（构建关系图）
 */
public class PlanToSkeletonCompilerV1 implements PlanToSkeletonCompiler {

    private final PlanNormalizationStep normalizer;
    private final StructuralExtractionStep extractor;
    private final SkeletonGenerationStep generator;
    private final SkeletonPostProcessStep postProcessor;

    public PlanToSkeletonCompilerV1() {
        // 使用默认实现
        this.normalizer = new PlanNormalizationStepV1();
        this.extractor = new StructuralExtractionStepV1();
        this.generator = new SkeletonGenerationStepV1();
        this.postProcessor = new SkeletonPostProcessStepV1();
    }

    public PlanToSkeletonCompilerV1(
            PlanNormalizationStep normalizer,
            StructuralExtractionStep extractor,
            SkeletonGenerationStep generator,
            SkeletonPostProcessStep postProcessor
    ) {
        this.normalizer = normalizer != null ? normalizer : new PlanNormalizationStepV1();
        this.extractor = extractor != null ? extractor : new StructuralExtractionStepV1();
        this.generator = generator != null ? generator : new SkeletonGenerationStepV1();
        this.postProcessor = postProcessor != null ? postProcessor : new SkeletonPostProcessStepV1();
    }

    @Override
    public CompiledSkeleton compile(PlanSkeleton planSkeleton, PlanCompileContext context) {
        if (planSkeleton == null) {
            return new CompiledSkeleton(List.of(), new SkeletonGraph());
        }

        if (context == null) {
            context = PlanCompileContext.createDefault();
        }

        // Step 1: Normalization（规范化）
        PlanSkeleton normalized = normalizer.normalize(planSkeleton);

        // Step 2: Structural Extraction（结构提取）
        StructuralSkeleton structural = extractor.extract(normalized, context);

        // Step 3: Skeleton Generation（骨架生成）
        List<ExecutableSkeletonPlan> skeletons = generator.generateSkeletons(structural, context);

        // Step 4: Post Processing（后处理）
        skeletons = postProcessor.postProcess(skeletons, context);

        // Step 5: Build Graph（构建关系图）
        SkeletonGraph graph = SkeletonGraphBuilder.build(skeletons, structural);

        return new CompiledSkeleton(skeletons, graph);
    }
}
