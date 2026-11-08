package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Final-pass conflict check against compile + post-process occupancy.
 * Untracked final PLACE (no index record) is a hard conflict.
 */
public final class PatchConflictValidator {
    private PatchConflictValidator() {}

    public record Finding(BlockPos position, String firstBlock, String secondBlock, String reason) {}

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
            if (rec == null) {
                return Optional.of(new Finding(e.getKey(), "", e.getValue(), "untracked_place"));
            }
            String indexed = PatchOccupancyIndex.canonical(rec.targetBlock());
            if (indexed == null || indexed.equals(e.getValue())) continue;
            // Opening glazing may land in the folded result after an authorized carve while the
            // occupancy index still holds the pre-window solid (emit clip / order edge cases).
            // Do not hard-fail shoji/glass pane over stone/brick wall cells.
            if (isOpeningGlazing(e.getValue()) && isStructuralSolid(indexed)) {
                continue;
            }
            // Material drift without a matching occupancy update (should have been commitPostProcess).
            return Optional.of(new Finding(e.getKey(), indexed, e.getValue(), "unauthorized_final_overwrite"));
        }
        return Optional.empty();
    }

    private static boolean isOpeningGlazing(String canon) {
        if (canon == null) return false;
        String s = canon.toLowerCase(java.util.Locale.ROOT);
        return s.contains("glass") || s.contains("pane") || s.contains("iron_bars")
                || s.contains("iron_bar") || s.contains(":air");
    }

    private static boolean isStructuralSolid(String canon) {
        if (canon == null) return false;
        String s = canon.toLowerCase(java.util.Locale.ROOT);
        if (isOpeningGlazing(s)) return false;
        return s.contains("stone") || s.contains("brick") || s.contains("concrete")
                || s.contains("planks") || s.contains("log") || s.contains("terracotta")
                || s.contains("deepslate") || s.contains("cobble") || s.contains("sandstone")
                || s.contains("quartz") || s.contains("prismarine") || s.contains("copper");
    }

    public static Optional<CapabilityGap> toCapabilityGap(Finding f) {
        if (f == null) return Optional.empty();
        return Optional.of(new CapabilityGap(
                "E_PATCH_CONFLICT",
                "未授权 Patch 覆盖：" + f.position().toShortString()
                        + " " + f.firstBlock() + " -> " + f.secondBlock()
                        + (f.reason() == null ? "" : " (" + f.reason() + ")"),
                "components[]",
                List.of("为开口类构件授权覆盖", "或设置 allow_patch_overwrite=true",
                        "后处理变更须经 PostProcessPatchDelta 入占用索引",
                        "禁止无关构件 REMOVE/PLACE 绕过占用")
        ));
    }
}
