package com.formacraft.common.patch.filter.impl;

import com.formacraft.client.tool.SelectionTool;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.filter.PatchFilter;
import com.formacraft.common.patch.filter.PatchFilterContext;

import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.stream.Collectors;

/**
 * SelectionOnlyFilter（只修改选区）
 * 
 * 核心功能：只保留位于选区内的 BlockPatch
 * 
 * 效果：
 * 🟩 "只修改选区内建筑"
 * 而且是硬约束
 */
public class SelectionOnlyFilter implements PatchFilter {

    @Override
    public List<BlockPatch> filter(
            List<BlockPatch> input,
            BlockPos origin,
            PatchFilterContext context
    ) {
        if (!context.hasSelection()) {
            return input;
        }

        SelectionTool sel = context.selection;
        BlockPos min = sel.getMin();
        BlockPos max = sel.getMax();

        if (min == null || max == null) {
            return input;
        }

        return input.stream()
                .filter(p -> {
                    BlockPos pos = origin.add(p.dx(), p.dy(), p.dz());
                    return contains(min, max, pos);
                })
                .collect(Collectors.toList());
    }

    /**
     * 检查位置是否在选区内
     */
    private boolean contains(BlockPos min, BlockPos max, BlockPos pos) {
        if (min == null || max == null || pos == null) {
            return false;
        }
        return pos.getX() >= min.getX() && pos.getX() <= max.getX()
                && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
    }
}

