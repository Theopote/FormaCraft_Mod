package com.formacraft.common.gen;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.skeleton.SkeletonPlan;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * SkeletonGenerator（生成器接口）
 * 
 * 核心职责：将 SkeletonPlan 转换为 BlockPatch 列表
 * 
 * 设计原则：
 * - Generator 只负责"长肉"（把骨架变成体块/构件）
 * - 不关心工具约束（由 PatchFilterPipeline 处理）
 * - 不关心风格（由 PaletteResolver 处理）
 */
public interface SkeletonGenerator {
    
    /**
     * 生成 BlockPatch 列表
     * 
     * @param origin 原点（BlockPatch 的相对坐标基准）
     * @param plan 骨架计划
     * @param ctx 生成器上下文
     * @return BlockPatch 列表（相对 origin）
     */
    List<BlockPatch> generate(BlockPos origin, SkeletonPlan plan, GeneratorContext ctx);
}

