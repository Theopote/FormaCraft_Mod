package com.formacraft.common.patch.filter;

import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * PatchFilter（可插拔的 Patch 过滤器接口）
 * 
 * 核心职责：过滤 BlockPatch 列表，确保满足工具约束
 * 
 * 设计原则：
 * - 每个 Filter 只负责一种约束
 * - Filter 可以组合使用（Pipeline）
 * - Filter 是最后一道闸门，确保安全
 */
public interface PatchFilter {

    /**
     * 过滤 BlockPatch 列表
     * 
     * @param input 输入的 BlockPatch 列表
     * @param origin 原点（BlockPatch 的相对坐标基准）
     * @param context 工具状态快照
     * @return 过滤后的 BlockPatch 列表
     */
    List<BlockPatch> filter(
            List<BlockPatch> input,
            BlockPos origin,
            PatchFilterContext context
    );
}
