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
 ) {
    /** Only final state differences belong in history and Memory, not attempted operations. */
    public static PatchTransaction fromSnapshots(BlockPos origin, Map<BlockPos, BlockState> before,
                                                  Map<BlockPos, BlockState> after) {
        Map<BlockPos, BlockState> changedBefore = new java.util.HashMap<>();
        Map<BlockPos, BlockState> changedAfter = new java.util.HashMap<>();
        for (var entry : before.entrySet()) {
            BlockState target = after.get(entry.getKey());
            if (target != null && !entry.getValue().equals(target)) {
                changedBefore.put(entry.getKey().toImmutable(), entry.getValue());
                changedAfter.put(entry.getKey().toImmutable(), target);
            }
        }
        return new PatchTransaction(origin.toImmutable(), patchesForStates(origin, changedAfter),
                Map.copyOf(changedBefore), Map.copyOf(changedAfter));
    }

    public static List<BlockPatch> patchesForStates(BlockPos origin, Map<BlockPos, BlockState> states) {
        return states.entrySet().stream()
                .sorted(java.util.Comparator.comparingInt((Map.Entry<BlockPos, BlockState> e) -> e.getKey().getX())
                        .thenComparingInt(e -> e.getKey().getY()).thenComparingInt(e -> e.getKey().getZ()))
                .map(e -> {
                    BlockPos relative = e.getKey().subtract(origin);
                    return new BlockPatch(e.getValue().isAir() ? BlockPatch.REMOVE : BlockPatch.REPLACE,
                            relative.getX(), relative.getY(), relative.getZ(),
                            com.formacraft.common.component.transform.BlockStateStringUtil.fromState(e.getValue()));
                }).toList();
    }
}

