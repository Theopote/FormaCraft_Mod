package com.formacraft.common.patch.filter.impl;

import com.formacraft.common.buildcontext.SelectionBox;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.filter.PatchFilter;
import com.formacraft.common.patch.filter.PatchFilterContext;

import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.stream.Collectors;

/**
 * SelectionOnlyFilter（只修改选区）
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

        SelectionBox sel = context.snapshot.selection;
        BlockPos min = sel.min();
        BlockPos max = sel.max();

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

    private boolean contains(BlockPos min, BlockPos max, BlockPos pos) {
        if (min == null || max == null || pos == null) {
            return false;
        }
        return pos.getX() >= min.getX() && pos.getX() <= max.getX()
                && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
    }
}
