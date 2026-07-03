package com.formacraft.common.patch.filter.impl;

import com.formacraft.common.buildcontext.OutlineShape;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.filter.PatchFilter;
import com.formacraft.common.patch.filter.PatchFilterContext;
import com.formacraft.common.tool.OutlineGeometry;

import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.stream.Collectors;

/**
 * OutlineClipFilter（轮廓裁切）
 */
public class OutlineClipFilter implements PatchFilter {

    @Override
    public List<BlockPatch> filter(
            List<BlockPatch> input,
            BlockPos origin,
            PatchFilterContext context
    ) {
        if (!context.hasOutline()) {
            return input;
        }

        OutlineShape shape = context.snapshot.outline;
        if (shape == null) {
            return input;
        }

        return input.stream()
                .filter(p -> {
                    BlockPos pos = origin.add(p.dx(), p.dy(), p.dz());
                    return OutlineGeometry.contains(shape, pos);
                })
                .collect(Collectors.toList());
    }
}
