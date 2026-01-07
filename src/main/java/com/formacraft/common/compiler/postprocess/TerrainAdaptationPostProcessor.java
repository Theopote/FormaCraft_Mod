package com.formacraft.common.compiler.postprocess;

import com.formacraft.common.llm.dto.GlobalConstraints;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.terrain.TerrainStrategySampler;
import com.formacraft.FormacraftMod;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * TerrainAdaptationPostProcessor（地形适应后处理器）
 * <p>
 * 根据地形策略，调整 BlockPatch 的 Y 坐标，使建筑适应地形。
 * <p>
 * 功能：
 * - PRESERVE：保持原样（不调整）
 * - ADAPTIVE：根据地形调整每个方块的高度
 * - TERRACE：创建平台（分段调整）
 * - FLATTEN：平整地形（调整到同一高度）
 */
public class TerrainAdaptationPostProcessor implements PostProcessor {

    private final ServerWorld world;
    private final TerrainStrategySampler terrainSampler;

    public TerrainAdaptationPostProcessor(ServerWorld world, TerrainStrategySampler terrainSampler) {
        this.world = world;
        this.terrainSampler = terrainSampler;
    }

    @Override
    public List<BlockPatch> process(List<BlockPatch> patches, PostProcessContext context) {
        if (patches == null || patches.isEmpty()) {
            return patches;
        }

        // 获取地形策略
        GlobalConstraints constraints = context.plan().globalConstraints();
        GlobalConstraints.TerrainStrategy strategy = (constraints != null && constraints.terrainStrategy() != null)
                ? constraints.terrainStrategy()
                : GlobalConstraints.TerrainStrategy.ADAPTIVE;

        // 如果策略是 PRESERVE，不进行任何调整
        if (strategy == GlobalConstraints.TerrainStrategy.PRESERVE) {
            return patches;
        }

        List<BlockPatch> result = new ArrayList<>(patches.size());
        BlockPos globalAnchor = context.globalAnchor();

        for (BlockPatch patch : patches) {
            if (patch == null) continue;

            // 计算世界坐标
            BlockPos worldPos = globalAnchor.add(patch.dx(), patch.dy(), patch.dz());

            // 根据策略调整 Y 坐标
            int adjustedY = adjustY(worldPos, strategy, context);
            int dy = adjustedY - worldPos.getY();

            // 创建调整后的 patch
            BlockPatch adjusted = new BlockPatch(
                    patch.action(),
                    patch.dx(),
                    patch.dy() + dy,
                    patch.dz(),
                    patch.targetBlock()
            );

            result.add(adjusted);
        }

        FormacraftMod.LOGGER.debug("TerrainAdaptationPostProcessor: adjusted {} patches for strategy {}", 
                result.size(), strategy);
        return result;
    }

    /**
     * 根据地形策略调整 Y 坐标
     */
    private int adjustY(BlockPos worldPos, GlobalConstraints.TerrainStrategy strategy, PostProcessContext context) {
        if (world == null || terrainSampler == null) {
            return worldPos.getY();
        }

        return switch (strategy) {
            case PRESERVE -> worldPos.getY(); // 保持原样
            case ADAPTIVE -> {
                // 适应地形：获取地面高度
                int groundY = terrainSampler.sampleGroundY(world, worldPos.getX(), worldPos.getZ());
                // 如果当前 Y 低于地面，调整到地面
                yield Math.max(worldPos.getY(), groundY);
            }
            case TERRACE -> {
                // 平台化：创建分段平台
                // 简化：调整到最近的地面高度
                yield terrainSampler.sampleGroundY(world, worldPos.getX(), worldPos.getZ());
            }
            case FLATTEN -> {
                // 平整：所有方块调整到同一高度（使用 anchor 的 Y）
                yield context.globalAnchor().getY();
            }
        };
    }
}

