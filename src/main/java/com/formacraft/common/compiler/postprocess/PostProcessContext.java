package com.formacraft.common.compiler.postprocess;

import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Vec3i;
import net.minecraft.util.math.BlockPos;

/**
 * PostProcessContext（后处理上下文）
 * 
 * 包含后处理器所需的所有上下文信息
 */
public record PostProcessContext(
        /** LLM Plan（包含 styleProfile、globalConstraints 等） */
        LlmPlan plan,
        
        /** 全局 anchor（世界坐标） */
        BlockPos globalAnchor,
        
        /** 相对 anchor（用于相对坐标计算） */
        Vec3i relativeAnchor
) {
    /**
     * 创建默认上下文
     */
    public static PostProcessContext create(LlmPlan plan, BlockPos globalAnchor) {
        Vec3i relAnchor = plan.anchor() != null 
                ? plan.anchor() 
                : new Vec3i(0, 0, 0);
        return new PostProcessContext(plan, globalAnchor, relAnchor);
    }
}

