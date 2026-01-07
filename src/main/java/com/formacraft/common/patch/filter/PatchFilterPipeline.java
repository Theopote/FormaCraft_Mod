package com.formacraft.common.patch.filter;

import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * PatchFilterPipeline（主入口）
 * 
 * 核心职责：组合多个 Filter，按顺序应用
 * 
 * 设计原则：
 * - Filter 按添加顺序执行
 * - 每个 Filter 的输出作为下一个 Filter 的输入
 * - 最终输出是经过所有 Filter 处理的安全 Patch 列表
 */
public class PatchFilterPipeline {

    private final List<PatchFilter> filters = new ArrayList<>();

    /**
     * 添加 Filter
     */
    public PatchFilterPipeline add(PatchFilter filter) {
        if (filter != null) {
            filters.add(filter);
        }
        return this;
    }

    /**
     * 应用所有 Filter
     * 
     * @param patches 输入的 BlockPatch 列表
     * @param origin 原点
     * @param context 工具状态快照
     * @return 过滤后的安全 BlockPatch 列表
     */
    public List<BlockPatch> apply(
            List<BlockPatch> patches,
            BlockPos origin,
            PatchFilterContext context
    ) {
        if (patches == null || patches.isEmpty()) {
            return new ArrayList<>();
        }

        List<BlockPatch> current = patches;
        for (PatchFilter filter : filters) {
            current = filter.filter(current, origin, context);
        }
        return current;
    }

    /**
     * 创建默认 Pipeline（推荐顺序）
     */
    public static PatchFilterPipeline createDefault() {
        return new PatchFilterPipeline()
                .add(new com.formacraft.common.patch.filter.impl.ForbiddenZoneFilter())
                .add(new com.formacraft.common.patch.filter.impl.OutlineClipFilter())
                .add(new com.formacraft.common.patch.filter.impl.SelectionOnlyFilter())
                .add(new com.formacraft.common.patch.filter.impl.SymmetryFilter());
    }
}

