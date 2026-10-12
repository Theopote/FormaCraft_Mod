package com.formacraft.common.patch.history;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.world.BlockSnapshot;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Patch 的一次“交易”快照：用于 Undo / Redo（含可选 BE NBT）。
 */
public record PatchTransaction(
        BlockPos origin,
        List<BlockPatch> patches,
        Map<BlockPos, BlockSnapshot> before,
        Map<BlockPos, BlockSnapshot> after
) {
    /** Only final state differences belong in history and Memory, not attempted operations. */
    public static PatchTransaction fromSnapshots(BlockPos origin, Map<BlockPos, BlockSnapshot> before,
                                                  Map<BlockPos, BlockSnapshot> after) {
        Map<BlockPos, BlockSnapshot> changedBefore = new HashMap<>();
        Map<BlockPos, BlockSnapshot> changedAfter = new HashMap<>();
        for (var entry : before.entrySet()) {
            BlockSnapshot target = after.get(entry.getKey());
            if (target != null && !entry.getValue().equals(target)) {
                changedBefore.put(entry.getKey().toImmutable(), entry.getValue());
                changedAfter.put(entry.getKey().toImmutable(), target);
            }
        }
        return new PatchTransaction(origin.toImmutable(), patchesForSnapshots(origin, changedAfter),
                Map.copyOf(changedBefore), Map.copyOf(changedAfter));
    }

    /** 兼容仅 BlockState 的快照入口。 */
    public static PatchTransaction fromStateSnapshots(BlockPos origin, Map<BlockPos, BlockState> before,
                                                      Map<BlockPos, BlockState> after) {
        return fromSnapshots(origin, wrapStates(before), wrapStates(after));
    }

    public static Map<BlockPos, BlockSnapshot> wrapStates(Map<BlockPos, BlockState> states) {
        Map<BlockPos, BlockSnapshot> out = new HashMap<>(states.size());
        for (var e : states.entrySet()) {
            out.put(e.getKey(), BlockSnapshot.of(e.getValue()));
        }
        return out;
    }

    public static List<BlockPatch> patchesForSnapshots(BlockPos origin, Map<BlockPos, BlockSnapshot> snapshots) {
        return snapshots.entrySet().stream()
                .sorted(java.util.Comparator.comparingInt((Map.Entry<BlockPos, BlockSnapshot> e) -> e.getKey().getX())
                        .thenComparingInt(e -> e.getKey().getY()).thenComparingInt(e -> e.getKey().getZ()))
                .map(e -> {
                    BlockPos relative = e.getKey().subtract(origin);
                    BlockState state = e.getValue().state();
                    return new BlockPatch(state.isAir() ? BlockPatch.REMOVE : BlockPatch.REPLACE,
                            relative.getX(), relative.getY(), relative.getZ(),
                            com.formacraft.common.component.transform.BlockStateStringUtil.fromState(state));
                }).toList();
    }

    /** @deprecated use {@link #patchesForSnapshots} */
    @Deprecated
    public static List<BlockPatch> patchesForStates(BlockPos origin, Map<BlockPos, ?> statesOrSnapshots) {
        if (statesOrSnapshots.isEmpty()) return List.of();
        Object sample = statesOrSnapshots.values().iterator().next();
        if (sample instanceof BlockSnapshot) {
            @SuppressWarnings("unchecked")
            Map<BlockPos, BlockSnapshot> map = (Map<BlockPos, BlockSnapshot>) statesOrSnapshots;
            return patchesForSnapshots(origin, map);
        }
        @SuppressWarnings("unchecked")
        Map<BlockPos, BlockState> map = (Map<BlockPos, BlockState>) statesOrSnapshots;
        return patchesForSnapshots(origin, wrapStates(map));
    }
}
