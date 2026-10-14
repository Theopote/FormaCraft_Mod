package com.formacraft.common.compiler.postprocess;

import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Restores protected circulation clearance cells after post-processors.
 * Emits an auditable delta (not a silent terminal rewrite).
 */
public final class ProtectedClearanceRestorer {
    public static final String PROCESSOR_ID = "ProtectedClearanceRestorer";

    private ProtectedClearanceRestorer() {}

    public record RestoreResult(List<BlockPatch> patches, PostProcessPatchDelta delta, PostProcessWriteScope scope) {}

    public static RestoreResult apply(
            List<BlockPatch> current,
            Map<BlockPos, BlockPatch> reserved,
            Set<BlockPos> clearance
    ) {
        if (reserved == null || reserved.isEmpty() || clearance == null || clearance.isEmpty()) {
            return new RestoreResult(current, new PostProcessPatchDelta(Map.of(), Map.of(), Map.of()),
                    PostProcessWriteScope.clearanceOnly(Set.of()));
        }
        List<BlockPatch> previous = current == null ? List.of() : List.copyOf(current);
        var preserved = new ArrayList<BlockPatch>();
        for (var patch : previous) {
            var pos = new BlockPos(patch.dx(), patch.dy(), patch.dz());
            if (!clearance.contains(pos)) preserved.add(patch);
        }
        preserved.addAll(reserved.values());
        PostProcessPatchDelta delta = PostProcessPatchDelta.diff(previous, preserved);
        Set<BlockPos> scopeCells = new java.util.HashSet<>(clearance);
        return new RestoreResult(preserved, delta, PostProcessWriteScope.clearanceOnly(scopeCells));
    }

    /** Snapshot clearance patches from the initial input list. */
    public static Map<BlockPos, BlockPatch> snapshotReserved(List<BlockPatch> patches, Set<BlockPos> clearance) {
        var reserved = new LinkedHashMap<BlockPos, BlockPatch>();
        if (patches == null || clearance == null || clearance.isEmpty()) return reserved;
        for (var patch : patches) {
            if (patch == null) continue;
            var pos = new BlockPos(patch.dx(), patch.dy(), patch.dz());
            if (clearance.contains(pos)) reserved.put(pos, patch);
        }
        return reserved;
    }
}
