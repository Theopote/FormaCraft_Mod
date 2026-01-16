package com.formacraft.common.mass.derived;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.*;
import java.util.stream.Collectors;

/**
 * SkeletonMerger（骨架合并器）
 * <p>
 * 🎯 核心职责：
 * 将多个 block face 合并成连续的 Skeleton 面
 * <p>
 * 合并规则（v1 必须）：
 * - 同一方向
 * - 连续（相邻）
 * - 同一高度区间
 * <p>
 * 这一步极大降低后续 Socket 数量。
 */
public final class SkeletonMerger {

    private SkeletonMerger() {}

    /**
     * 合并 Skeleton 列表
     * <p>
     * 将满足合并条件的 Skeleton 合并成更大的连续面
     *
     * @param skeletons 原始 Skeleton 列表
     * @return 合并后的 Skeleton 列表
     */
    public static List<MassDerivedSkeleton> mergeSkeletons(List<MassDerivedSkeleton> skeletons) {
        if (skeletons == null || skeletons.isEmpty()) {
            return List.of();
        }

        // 按 kind、context、facing 分组
        Map<SkeletonGroupKey, List<MassDerivedSkeleton>> grouped = skeletons.stream()
                .collect(Collectors.groupingBy(s -> new SkeletonGroupKey(
                        s.kind, s.context, s.facing
                )));

        List<MassDerivedSkeleton> merged = new ArrayList<>();

        for (Map.Entry<SkeletonGroupKey, List<MassDerivedSkeleton>> entry : grouped.entrySet()) {
            List<MassDerivedSkeleton> group = entry.getValue();

            // 对于同一组的 Skeleton，尝试合并
            merged.addAll(mergeGroup(group));
        }

        return merged;
    }

    /**
     * 合并同一组的 Skeleton
     * <p>
     * v1 简化：如果高度区间重叠，则合并成一个
     * 未来：需要更精确的连续性检测（相邻方块检测）
     */
    private static List<MassDerivedSkeleton> mergeGroup(List<MassDerivedSkeleton> group) {
        if (group.isEmpty()) {
            return List.of();
        }

        if (group.size() == 1) {
            return group;
        }

        // v1 简化：合并所有位置，取最小/最大高度
        Set<BlockPos> allPositions = new HashSet<>();
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;

        for (MassDerivedSkeleton skeleton : group) {
            allPositions.addAll(skeleton.positions);
            minY = Math.min(minY, skeleton.minY);
            maxY = Math.max(maxY, skeleton.maxY);
        }

        // 创建合并后的 Skeleton
        MassDerivedSkeleton first = group.get(0);
        return List.of(new MassDerivedSkeleton(
                "merged_" + first.id + "_" + System.nanoTime(),
                first.kind,
                first.context,
                first.facing,
                new ArrayList<>(allPositions),
                minY,
                maxY
        ));
    }

    /**
     * Skeleton 分组键
     */
    private record SkeletonGroupKey(
            MassDerivedSkeleton.SkeletonKind kind,
            MassDerivedSkeleton.SkeletonContext context,
            Direction facing
    ) {}
}
