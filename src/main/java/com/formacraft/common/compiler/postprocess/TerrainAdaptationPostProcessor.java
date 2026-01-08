package com.formacraft.common.compiler.postprocess;

import com.formacraft.common.llm.dto.GlobalConstraints;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.terrain.TerrainStrategySampler;
import com.formacraft.FormacraftMod;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

        BlockPos globalAnchor = context.globalAnchor();

        Map<Long, Integer> minDyByColumn = new HashMap<>();
        for (BlockPatch patch : patches) {
            if (patch == null) continue;
            long key = packColumn(patch.dx(), patch.dz());
            int dy = patch.dy();
            minDyByColumn.merge(key, dy, Math::min);
        }

        Map<Long, Integer> shiftByColumn = new HashMap<>();
        for (Map.Entry<Long, Integer> entry : minDyByColumn.entrySet()) {
            long key = entry.getKey();
            int dx = unpackColumnX(key);
            int dz = unpackColumnZ(key);
            int baseDy = entry.getValue();

            int worldX = globalAnchor.getX() + dx;
            int worldZ = globalAnchor.getZ() + dz;
            int worldBaseY = globalAnchor.getY() + baseDy;

            int groundY = terrainSampler.sampleGroundY(world, worldX, worldZ);
            int rawShift = groundY - worldBaseY;
            int shift = resolveShiftForStrategy(rawShift, strategy, context, worldBaseY);
            shiftByColumn.put(key, shift);
        }

        List<BlockPatch> result = new ArrayList<>(patches.size());
        for (BlockPatch patch : patches) {
            if (patch == null) continue;
            long key = packColumn(patch.dx(), patch.dz());
            int shift = shiftByColumn.getOrDefault(key, 0);
            BlockPatch adjusted = new BlockPatch(
                    patch.action(),
                    patch.dx(),
                    patch.dy() + shift,
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
     * 根据地形策略调整柱子的偏移
     */
    private int resolveShiftForStrategy(int rawShift, GlobalConstraints.TerrainStrategy strategy,
                                        PostProcessContext context, int worldBaseY) {
        if (world == null || terrainSampler == null) {
            return 0;
        }

        return switch (strategy) {
            case PRESERVE -> 0;
            case ADAPTIVE -> clampShift(rawShift, 3);
            case TERRACE -> {
                int stepped = quantizeShift(rawShift, 2);
                yield clampShift(stepped, 6);
            }
            case FLATTEN -> context.globalAnchor().getY() - worldBaseY;
        };
    }

    private static int clampShift(int shift, int limit) {
        int lim = Math.max(0, limit);
        if (lim == 0) return 0;
        return Math.max(-lim, Math.min(lim, shift));
    }

    private static int quantizeShift(int shift, int step) {
        int s = Math.max(1, step);
        return Math.round(shift / (float) s) * s;
    }

    private static long packColumn(int dx, int dz) {
        return (((long) dx) << 32) ^ (dz & 0xffffffffL);
    }

    private static int unpackColumnX(long key) {
        return (int) (key >> 32);
    }

    private static int unpackColumnZ(long key) {
        return (int) key;
    }
}

