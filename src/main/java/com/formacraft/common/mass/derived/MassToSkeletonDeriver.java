package com.formacraft.common.mass.derived;

import com.formacraft.common.mass.BuildingMassComposition;
import com.formacraft.common.mass.MassFilledChecker;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.*;

/**
 * MassToSkeletonDeriver（从体量派生骨架）
 * <p>
 * 🎯 核心定位（架构校准 2026-01-14）：
 * 从 BuildingMass 派生"可装配的结构面（Skeleton / Socket）"
 * <p>
 * 派生规则：
 * 1. 外暴露边界 → Exterior Skeleton
 * 2. 内接触边界 → Interface Skeleton
 * 3. 顶部边界 → Top Skeleton
 * <p>
 * ⚠️ 关键：
 * - 所有判断都基于 isFilled
 * - 不关心多边形 / 曲面
 * - 可 chunk-based
 * - 可逐步生成
 */
public final class MassToSkeletonDeriver {

    private MassToSkeletonDeriver() {}

    /**
     * 从体量组合派生所有 Skeleton
     * <p>
     * 派生顺序：
     * 1. Exterior Skeleton（外暴露边界）
     * 2. Interface Skeleton（内接触边界）
     * 3. Top Skeleton（顶部边界）
     *
     * @param composition 体量组合
     * @param scanBounds 扫描范围（需要派生的区域）
     * @return 派生出的 Skeleton 列表
     */
    public static List<MassDerivedSkeleton> deriveSkeletons(
            BuildingMassComposition composition,
            Bounds scanBounds
    ) {
        if (composition == null || scanBounds == null) {
            return List.of();
        }

        List<MassDerivedSkeleton> skeletons = new ArrayList<>();

        // 1. 派生 Exterior Skeleton
        skeletons.addAll(deriveExteriorSkeletons(composition, scanBounds));

        // 2. 派生 Interface Skeleton
        skeletons.addAll(deriveInterfaceSkeletons(composition, scanBounds));

        // 3. 派生 Top Skeleton
        skeletons.addAll(deriveTopSkeletons(composition, scanBounds));

        return skeletons;
    }

    /**
     * 派生 Exterior Skeleton（外暴露边界）
     * <p>
     * 判定条件：
     * - isFilled(x,y,z) == true
     * - isFilled(x+dir.x, y+dir.y, z+dir.z) == false
     * <p>
     * 这个"面"就是一个 Exterior Face
     */
    private static List<MassDerivedSkeleton> deriveExteriorSkeletons(
            BuildingMassComposition composition,
            Bounds bounds
    ) {
        List<MassDerivedSkeleton> skeletons = new ArrayList<>();

        // 遍历所有水平方向（不包括 UP/DOWN）
        Direction[] horizontalDirs = {
                Direction.NORTH, Direction.SOUTH,
                Direction.EAST, Direction.WEST
        };

        for (Direction dir : horizontalDirs) {
            List<BlockPos> exteriorPositions = new ArrayList<>();

            // 扫描范围内的所有方块
            for (int x = bounds.minX; x <= bounds.maxX; x++) {
                for (int y = bounds.minY; y <= bounds.maxY; y++) {
                    for (int z = bounds.minZ; z <= bounds.maxZ; z++) {
                        if (MassFilledChecker.isExteriorBoundary(composition, x, y, z, dir)) {
                            exteriorPositions.add(new BlockPos(x, y, z));
                        }
                    }
                }
            }

            if (!exteriorPositions.isEmpty()) {
                // 计算高度区间
                int minY = exteriorPositions.stream().mapToInt(BlockPos::getY).min().orElse(bounds.minY);
                int maxY = exteriorPositions.stream().mapToInt(BlockPos::getY).max().orElse(bounds.maxY);

                skeletons.add(new MassDerivedSkeleton(
                        "exterior_" + dir.name() + "_" + System.nanoTime(),
                        MassDerivedSkeleton.SkeletonKind.WALL,
                        MassDerivedSkeleton.SkeletonContext.EXTERIOR,
                        dir,
                        exteriorPositions,
                        minY,
                        maxY
                ));
            }
        }

        return skeletons;
    }

    /**
     * 派生 Interface Skeleton（内接触边界）
     * <p>
     * 判定条件：
     * - isFilled(x,y,z) == true
     * - isFilled(x+dir.x, y+dir.y, z+dir.z) == true
     * - 但来自不同的 BuildingMass 或高度区间不同
     * <p>
     * ⚠️ v1 简化：只检查是否都被占用
     */
    private static List<MassDerivedSkeleton> deriveInterfaceSkeletons(
            BuildingMassComposition composition,
            Bounds bounds
    ) {
        List<MassDerivedSkeleton> skeletons = new ArrayList<>();

        Direction[] horizontalDirs = {
                Direction.NORTH, Direction.SOUTH,
                Direction.EAST, Direction.WEST
        };

        for (Direction dir : horizontalDirs) {
            List<BlockPos> interfacePositions = new ArrayList<>();

            // 扫描范围内的所有方块
            for (int x = bounds.minX; x <= bounds.maxX; x++) {
                for (int y = bounds.minY; y <= bounds.maxY; y++) {
                    for (int z = bounds.minZ; z <= bounds.maxZ; z++) {
                        if (MassFilledChecker.isInterfaceBoundary(composition, x, y, z, dir)) {
                            interfacePositions.add(new BlockPos(x, y, z));
                        }
                    }
                }
            }

            if (!interfacePositions.isEmpty()) {
                int minY = interfacePositions.stream().mapToInt(BlockPos::getY).min().orElse(bounds.minY);
                int maxY = interfacePositions.stream().mapToInt(BlockPos::getY).max().orElse(bounds.maxY);

                skeletons.add(new MassDerivedSkeleton(
                        "interface_" + dir.name() + "_" + System.nanoTime(),
                        MassDerivedSkeleton.SkeletonKind.WALL,
                        MassDerivedSkeleton.SkeletonContext.CONNECTION,
                        dir,
                        interfacePositions,
                        minY,
                        maxY
                ));
            }
        }

        return skeletons;
    }

    /**
     * 派生 Top Skeleton（顶部边界）
     * <p>
     * 判定条件：
     * - isFilled(x,y,z) == true
     * - isFilled(x, y+1, z) == false
     * <p>
     * 这是一个 Top Face
     */
    private static List<MassDerivedSkeleton> deriveTopSkeletons(
            BuildingMassComposition composition,
            Bounds bounds
    ) {
        List<BlockPos> topPositions = new ArrayList<>();

        // 扫描范围内的所有方块
        for (int x = bounds.minX; x <= bounds.maxX; x++) {
            for (int y = bounds.minY; y <= bounds.maxY; y++) {
                for (int z = bounds.minZ; z <= bounds.maxZ; z++) {
                    if (MassFilledChecker.isTopBoundary(composition, x, y, z)) {
                        topPositions.add(new BlockPos(x, y, z));
                    }
                }
            }
        }

        if (topPositions.isEmpty()) {
            return List.of();
        }

        int minY = topPositions.stream().mapToInt(BlockPos::getY).min().orElse(bounds.minY);
        int maxY = topPositions.stream().mapToInt(BlockPos::getY).max().orElse(bounds.maxY);

        // v1 简化：所有 Top 位置合并成一个 Skeleton
        // 未来：可以按 MassRole 区分（PRIMARY → ROOF, CANTILEVER → TERRACE）
        return List.of(new MassDerivedSkeleton(
                "top_" + System.nanoTime(),
                MassDerivedSkeleton.SkeletonKind.ROOF,
                MassDerivedSkeleton.SkeletonContext.EXTERIOR,
                Direction.UP,
                topPositions,
                minY,
                maxY
        ));
    }

    /**
     * 扫描范围
     */
    public record Bounds(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        /**
         * 创建一个包含所有体量的扫描范围
         * <p>
         * v1 简化：返回一个默认范围
         * 未来：需要从 composition 计算实际范围
         */
        public static Bounds fromComposition(BuildingMassComposition composition) {
            // v1 简化：返回默认范围
            // 未来：遍历所有体量，计算实际边界
            return new Bounds(-50, 50, 0, 100, -50, 50);
        }
    }
}
