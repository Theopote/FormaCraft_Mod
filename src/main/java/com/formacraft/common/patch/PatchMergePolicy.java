package com.formacraft.common.patch;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.component.util.GeneratedSurfaceCapture;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 冲突感知的 Patch 合并：同坐标时 BuildingMass 可覆盖 Skeleton 壳层，
 * 但 Skeleton 开口（空气/remove/门/玻璃）不被 Mass 实心填堵。
 */
public final class PatchMergePolicy {
    private PatchMergePolicy() {}

    public record MergeStats(int skeletonOnly, int massOnly, int overridden, int protectedOpenings, int total) {}

    public record MergeResult(List<BlockPatch> patches, MergeStats stats) {}

    /**
     * @param skeleton 先写路径（PlanSkeleton/PlanProgram 编译结果）
     * @param mass     后写体块路径（BuildingMass）；冲突时覆盖壳层，保护开口
     */
    public static MergeResult mergeSkeletonWithBuildingMass(List<BlockPatch> skeleton, List<BlockPatch> mass) {
        List<BlockPatch> sk = skeleton == null ? List.of() : skeleton;
        List<BlockPatch> ms = mass == null ? List.of() : mass;
        if (ms.isEmpty()) {
            return new MergeResult(List.copyOf(sk), new MergeStats(sk.size(), 0, 0, 0, sk.size()));
        }
        if (sk.isEmpty()) {
            return new MergeResult(List.copyOf(ms), new MergeStats(0, ms.size(), 0, 0, ms.size()));
        }

        Map<BlockPos, BlockPatch> byPos = new LinkedHashMap<>();
        for (BlockPatch p : sk) {
            if (p == null) continue;
            byPos.put(posKey(p), p);
        }
        int skeletonOnly = byPos.size();
        int overridden = 0;
        int massOnly = 0;
        int protectedOpenings = 0;
        for (BlockPatch p : ms) {
            if (p == null) continue;
            BlockPos k = posKey(p);
            BlockPatch existing = byPos.get(k);
            if (existing != null) {
                if (isProtectedOpening(existing) && GeneratedSurfaceCapture.occupied(p)) {
                    protectedOpenings++;
                    continue; // keep skeleton opening
                }
                overridden++;
                skeletonOnly--;
                byPos.put(k, p);
            } else {
                massOnly++;
                byPos.put(k, p);
            }
        }
        List<BlockPatch> out = new ArrayList<>(byPos.values());
        MergeStats stats = new MergeStats(Math.max(0, skeletonOnly), massOnly, overridden, protectedOpenings, out.size());
        FormacraftMod.LOGGER.info(
                "PatchMergePolicy: skeletonOnly={} massOnly={} overridden={} protectedOpenings={} total={}",
                stats.skeletonOnly(), stats.massOnly(), stats.overridden(),
                stats.protectedOpenings(), stats.total());
        return new MergeResult(out, stats);
    }

    static boolean isProtectedOpening(BlockPatch patch) {
        if (patch == null) return false;
        String action = patch.action() == null ? "" : patch.action().trim().toLowerCase(Locale.ROOT);
        if (BlockPatch.REMOVE.equals(action) || "air".equals(action)) return true;
        if (!GeneratedSurfaceCapture.occupied(patch)) return true;
        String id = normalizeId(patch.targetBlock());
        return id.endsWith("_door") || id.contains("trapdoor")
                || id.contains("glass") || id.contains("iron_bars");
    }

    private static BlockPos posKey(BlockPatch p) {
        return new BlockPos(p.dx(), p.dy(), p.dz());
    }

    private static String normalizeId(String value) {
        if (value == null) return "";
        int bracket = value.indexOf('[');
        String id = (bracket < 0 ? value : value.substring(0, bracket)).trim().toLowerCase(Locale.ROOT);
        return id.contains(":") ? id : "minecraft:" + id;
    }
}
