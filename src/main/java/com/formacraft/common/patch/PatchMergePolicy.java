package com.formacraft.common.patch;

import com.formacraft.FormacraftMod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
/**
 * 冲突感知的 Patch 合并：同坐标时 BuildingMass（体块壳）覆盖 Skeleton 同坐标体块。
 * <p>
 * 启发式（BlockPatch 无 role）：Mass 的 place/replace 覆盖先写的 Skeleton；
 * remove 以更晚写入为准；无重叠坐标取并集。
 */
public final class PatchMergePolicy {
    private PatchMergePolicy() {}

    public record MergeStats(int skeletonOnly, int massOnly, int overridden, int total) {}

    public record MergeResult(List<BlockPatch> patches, MergeStats stats) {}

    /**
     * @param skeleton 先写路径（PlanSkeleton/PlanProgram 编译结果）
     * @param mass     后写体块路径（BuildingMass）；冲突时覆盖
     */
    public static MergeResult mergeSkeletonWithBuildingMass(List<BlockPatch> skeleton, List<BlockPatch> mass) {
        List<BlockPatch> sk = skeleton == null ? List.of() : skeleton;
        List<BlockPatch> ms = mass == null ? List.of() : mass;
        if (ms.isEmpty()) {
            return new MergeResult(List.copyOf(sk), new MergeStats(sk.size(), 0, 0, sk.size()));
        }
        if (sk.isEmpty()) {
            return new MergeResult(List.copyOf(ms), new MergeStats(0, ms.size(), 0, ms.size()));
        }

        Map<Long, BlockPatch> byPos = new LinkedHashMap<>();
        for (BlockPatch p : sk) {
            if (p == null) continue;
            byPos.put(key(p), p);
        }
        int skeletonOnly = byPos.size();
        int overridden = 0;
        int massOnly = 0;
        for (BlockPatch p : ms) {
            if (p == null) continue;
            long k = key(p);
            if (byPos.containsKey(k)) {
                overridden++;
                skeletonOnly--;
            } else {
                massOnly++;
            }
            byPos.put(k, p);
        }
        List<BlockPatch> out = new ArrayList<>(byPos.values());
        MergeStats stats = new MergeStats(Math.max(0, skeletonOnly), massOnly, overridden, out.size());
        FormacraftMod.LOGGER.info(
                "PatchMergePolicy: skeletonOnly={} massOnly={} overridden={} total={}",
                stats.skeletonOnly(), stats.massOnly(), stats.overridden(), stats.total());
        return new MergeResult(out, stats);
    }

    private static long key(BlockPatch p) {
        // pack dx,dy,dz into long (each in reasonable building range)
        return (((long) p.dx()) & 0x1FFFFFL)
                | ((((long) p.dy()) & 0x1FFFFFL) << 21)
                | ((((long) p.dz()) & 0x1FFFFFL) << 42);
    }
}
