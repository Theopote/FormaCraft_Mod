package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Final-pass conflict check that reuses compile-time {@link PatchOccupancyIndex} authorization.
 * Does not re-guess overwrite policy from raw PLACE pairs alone.
 */
public final class PatchConflictValidator {
    private PatchConflictValidator() {}

    public record Finding(BlockPos position, String firstBlock, String secondBlock, String reason) {}

    /**
     * Fold {@code result} to final PLACE per cell, then compare against occupancy records.
     * Unauthorized material changes vs a non-overwrite PlaceRecord → conflict.
     */
    public static Optional<Finding> inspectAuthorized(List<BlockPatch> result, PatchOccupancyIndex index) {
        if (result == null || result.isEmpty() || index == null) return Optional.empty();
        Map<BlockPos, String> finalPlace = new HashMap<>();
        for (BlockPatch p : result) {
            if (p == null) continue;
            BlockPos pos = new BlockPos(p.dx(), p.dy(), p.dz());
            if (BlockPatch.REMOVE.equals(p.action())) {
                finalPlace.remove(pos);
                continue;
            }
            if (!BlockPatch.PLACE.equals(p.action())) continue;
            String canon = PatchOccupancyIndex.canonical(p.targetBlock());
            if (canon != null) finalPlace.put(pos, canon);
        }
        for (Map.Entry<BlockPos, String> e : finalPlace.entrySet()) {
            PatchOccupancyIndex.PlaceRecord rec = index.placeAt(e.getKey());
            if (rec == null) continue; // post-process new cell / never indexed
            String indexed = PatchOccupancyIndex.canonical(rec.targetBlock());
            if (indexed == null || indexed.equals(e.getValue())) continue;
            if (rec.authorizedOverwrite()) continue;
            return Optional.of(new Finding(e.getKey(), indexed, e.getValue(), "unauthorized_final_overwrite"));
        }
        return Optional.empty();
    }

    public static Optional<CapabilityGap> toCapabilityGap(Finding f) {
        if (f == null) return Optional.empty();
        return Optional.of(new CapabilityGap(
                "E_PATCH_CONFLICT",
                "未授权 Patch 覆盖：" + f.position().toShortString()
                        + " " + f.firstBlock() + " -> " + f.secondBlock()
                        + (f.reason() == null ? "" : " (" + f.reason() + ")"),
                "components[]",
                List.of("为开口类构件授权覆盖", "或设置 allow_patch_overwrite=true", "禁止无关构件互相覆盖同坐标")
        ));
    }
}
