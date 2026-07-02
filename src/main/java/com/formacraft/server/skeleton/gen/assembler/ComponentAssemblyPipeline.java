package com.formacraft.server.skeleton.gen.assembler;

import com.formacraft.common.component.ComponentPlan;
import com.formacraft.common.component.ComponentSpec;
import com.formacraft.common.semantic.SemanticPlacementOp;
import com.formacraft.common.skeleton.ExecutableSkeletonPlan;
import com.formacraft.server.skeleton.gen.GenerationContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 组件总装配流程
 * 
 * Component → Semantic
 * 
 * 将 ComponentPlan 中的所有组件装配到 Skeleton 上，生成 SemanticPlacementOp 列表
 */
public final class ComponentAssemblyPipeline {

    private ComponentAssemblyPipeline() {}

    /**
     * 装配所有组件
     * 
     * @param ctx 生成上下文
     * @param skeleton 骨架计划
     * @param components 组件计划
     * @return 语义放置操作列表
     */
    public static List<SemanticPlacementOp> assembleAll(
            GenerationContext ctx,
            ExecutableSkeletonPlan skeleton,
            ComponentPlan components
    ) {
        List<SemanticPlacementOp> all = new ArrayList<>();

        if (ctx == null || skeleton == null || components == null || components.isEmpty()) {
            return all;
        }

        for (ComponentSpec spec : components.getComponents()) {
            if (spec == null || spec.type == null) continue;

            ComponentAssembler assembler = ComponentAssemblerRegistry.get(spec.type);
            if (assembler == null) {
                // 如果没有装配器，跳过（可以记录日志）
                continue;
            }

            List<SemanticPlacementOp> ops = assembler.assemble(ctx, skeleton, spec);
            if (ops != null) {
                all.addAll(ops);
            }
        }

        return all;
    }
}

