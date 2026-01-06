package com.formacraft.server.skeleton.gen.path;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.skeleton.PathSkeleton;
import com.formacraft.common.terrain.TerrainPolicy;
import com.formacraft.common.terrain.TerrainStrategy;
import com.formacraft.server.skeleton.gen.GenerationContext;
import com.formacraft.server.skeleton.gen.ExecutableSkeletonPlan;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * BasePathGenerator（路径生成器基类）
 * 
 * 提供通用的路径生成逻辑：
 * - 地形采样
 * - 高度调整（根据 TerrainStrategy）
 * - 走廊生成
 */
public abstract class BasePathGenerator {

    /**
     * 生成路径段的目标高度
     * 
     * 强制规则（非常重要）：
     * - PRESERVE: 路径贴地，允许台阶
     * - ADAPTIVE: 局部垫高/削低
     * - TERRACE: 分段平台 + 台阶
     * - FLATTEN: ❌ 禁止（除非用户明确）
     */
    protected int computeTargetY(
            GenerationContext ctx,
            BlockPos node,
            TerrainPolicy terrainPolicy
    ) {
        int groundY = ctx.getSurfaceY(node.getX(), node.getZ());
        int nodeY = node.getY();

        return switch (terrainPolicy.strategy) {
            case PRESERVE -> {
                // 路径贴地，允许台阶
                yield groundY;
            }
            case ADAPTIVE -> {
                // 局部垫高/削低（限制在 maxCutDepth 和 maxFillHeight 内）
                int minY = groundY - terrainPolicy.maxCutDepth;
                int maxY = groundY + terrainPolicy.maxFillHeight;
                yield Math.max(minY, Math.min(maxY, nodeY));
            }
            case TERRACE -> {
                // 分段平台 + 台阶（简化：使用最近的平台高度）
                yield snapToTerraceLevel(ctx, node, groundY);
            }
            case FLATTEN -> {
                // ❌ 禁止（除非用户明确）
                // 如果用户明确要求 FLATTEN，使用全局基准高度
                if (terrainPolicy.scope == com.formacraft.common.terrain.TerrainPolicy.Scope.ALL) {
                    yield groundY; // 简化：使用地表高度
                } else {
                    // 默认不允许 FLATTEN
                    yield groundY;
                }
            }
        };
    }

    /**
     * 将高度对齐到最近的平台级别（用于 TERRACE）
     */
    private int snapToTerraceLevel(GenerationContext ctx, BlockPos node, int groundY) {
        // 简化实现：每 4 格一个平台
        int platformHeight = 4;
        int level = (groundY / platformHeight) * platformHeight;
        return level;
    }

    /**
     * 生成路径走廊内的方块
     */
    protected List<BlockPatch> generateCorridor(
            GenerationContext ctx,
            List<BlockPos> nodes,
            TerrainPolicy terrainPolicy,
            String blockId
    ) {
        List<BlockPatch> patches = new ArrayList<>();
        if (nodes == null || nodes.size() < 2) {
            return patches;
        }

        // 遍历路径段
        for (int i = 0; i < nodes.size() - 1; i++) {
            BlockPos from = nodes.get(i);
            BlockPos to = nodes.get(i + 1);

            // 计算目标高度
            int fromY = computeTargetY(ctx, from, terrainPolicy);
            int toY = computeTargetY(ctx, to, terrainPolicy);

            // 生成路径段（简化：直线插值）
            int steps = Math.max(1, (int) Math.ceil(Math.sqrt(
                    (to.getX() - from.getX()) * (to.getX() - from.getX()) +
                    (to.getZ() - from.getZ()) * (to.getZ() - from.getZ())
            )));

            for (int j = 0; j <= steps; j++) {
                double t = (double) j / steps;
                int x = (int) Math.round(from.getX() + (to.getX() - from.getX()) * t);
                int y = (int) Math.round(fromY + (toY - fromY) * t);
                int z = (int) Math.round(from.getZ() + (to.getZ() - from.getZ()) * t);

                BlockPos pos = new BlockPos(x, y, z);
                BlockPos relative = pos.subtract(ctx.origin);

                patches.add(new BlockPatch(BlockPatch.PLACE,
                        relative.getX(), relative.getY(), relative.getZ(), blockId));

                if (patches.size() >= ctx.maxOps) return patches;
            }
        }

        return patches;
    }
}

