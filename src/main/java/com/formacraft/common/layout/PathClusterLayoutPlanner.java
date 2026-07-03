package com.formacraft.common.layout;

import com.formacraft.common.terrain.TerrainStrategySampler;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * PathClusterLayoutPlanner（沿路径聚落布局规划器）
 *
 * 提供一个轻量、稳定的默认实现：
 * - 按给定 spacing 沿路径采样候选点
 * - 通过 lateralOffset 在路径侧向偏移
 * - 使用 TerrainStrategySampler 将点贴合到地表高度
 * - 通过 LayoutConstraints 过滤不合法站点
 */
public final class PathClusterLayoutPlanner {

    public static final class Options {
        public int spacing = 12;
        public int footprintW = 9;
        public int footprintD = 11;
        public int clearance = 2;
        public int lateralOffset = 5;
        public String tag = "path_cluster";
    }

    private final TerrainStrategySampler terrainSampler;

    public PathClusterLayoutPlanner(TerrainStrategySampler terrainSampler) {
        this.terrainSampler = terrainSampler != null ? terrainSampler : new TerrainStrategySampler();
    }

    public List<LayoutSite> plan(
            ServerWorld world,
            List<BlockPos> pathPoints,
            BlockPos origin,
            Options opt,
            LayoutConstraints constraints
    ) {
        if (world == null || pathPoints == null || pathPoints.size() < 2) {
            return Collections.emptyList();
        }

        Options options = opt != null ? opt : new Options();
        LayoutConstraints rules = constraints != null ? constraints : LayoutConstraints.ALLOW_ALL;

        int spacing = Math.max(1, options.spacing);
        List<LayoutSite> sites = new ArrayList<>();
        int distanceSinceLast = spacing;

        for (int i = 0; i < pathPoints.size() - 1; i++) {
            BlockPos a = pathPoints.get(i);
            BlockPos b = pathPoints.get(i + 1);

            int dx = b.getX() - a.getX();
            int dz = b.getZ() - a.getZ();
            int steps = Math.max(Math.abs(dx), Math.abs(dz));
            if (steps <= 0) {
                continue;
            }

            Direction facing = horizontalFacing(dx, dz);
            int sideX = -facing.getOffsetZ();
            int sideZ = facing.getOffsetX();

            for (int s = 0; s <= steps; s++) {
                if (distanceSinceLast < spacing) {
                    distanceSinceLast++;
                    continue;
                }

                double t = (double) s / (double) steps;
                int x = lerpInt(a.getX(), b.getX(), t) + sideX * options.lateralOffset;
                int z = lerpInt(a.getZ(), b.getZ(), t) + sideZ * options.lateralOffset;
                int y = terrainSampler.sampleGroundY(world, x, z);

                BlockPos anchor = new BlockPos(x, y, z);
                if (origin != null) {
                    anchor = anchor.subtract(origin).add(origin);
                }

                if (!rules.allowAnchor(anchor)) {
                    continue;
                }
                if (!rules.allowFootprint(anchor, options.footprintW, options.footprintD, options.clearance)) {
                    continue;
                }

                sites.add(new LayoutSite(
                        anchor,
                        facing,
                        options.footprintW,
                        options.footprintD,
                        options.clearance,
                        options.tag
                ));
                distanceSinceLast = 0;
            }
        }

        return sites;
    }

    private static int lerpInt(int a, int b, double t) {
        return (int) Math.round(a + (b - a) * t);
    }

    private static Direction horizontalFacing(int dx, int dz) {
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
