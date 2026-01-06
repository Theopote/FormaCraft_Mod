package com.formacraft.server.skeleton.gen.assembler;

import com.formacraft.common.component.ComponentSpec;
import com.formacraft.common.semantic.SemanticPlacementOp;
import com.formacraft.server.skeleton.gen.ExecutableSkeletonPlan;
import com.formacraft.server.skeleton.gen.GenerationContext;

import java.util.List;

/**
 * 组件装配器接口
 * 
 * Assembler = 把 Component → Skeleton 上的规则
 * 
 * 注意：Assembler 不关心 Palette，只关心"在哪里 + 是什么语义"
 */
public interface ComponentAssembler {

    /**
     * 把一个组件，装配到 Skeleton 上
     * 
     * @param ctx 生成上下文
     * @param skeleton 骨架计划
     * @param component 组件规格
     * @return 语义放置操作列表
     */
    List<SemanticPlacementOp> assemble(
            GenerationContext ctx,
            ExecutableSkeletonPlan skeleton,
            ComponentSpec component
    );
}

