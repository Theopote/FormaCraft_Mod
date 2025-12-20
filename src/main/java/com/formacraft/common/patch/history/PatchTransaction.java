package com.formacraft.common.patch.history;

import com.formacraft.common.patch.BlockPatch;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Map;

/**
 * Patch 的一次“交易”快照：用于 Undo / Redo。
 */
public record PatchTransaction(
        BlockPos origin,
        List<BlockPatch> patches,
        Map<BlockPos, BlockState> before,
        Map<BlockPos, BlockState> after
) {}

