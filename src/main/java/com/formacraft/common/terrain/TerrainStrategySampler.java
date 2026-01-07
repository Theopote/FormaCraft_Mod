package com.formacraft.common.terrain;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

/**
 * TerrainStrategySampler（地形策略采样器）
 * 
 * v1: sample top solid block at x,z.
 * 
 * 后续扩展方向：
 * - water handling
 * - cliffs
 * - smoothing window
 * - stairs / bridges
 */
public class TerrainStrategySampler {

    /**
     * 采样地面高度
     * 
     * @param world 世界
     * @param x X 坐标
     * @param z Z 坐标
     * @return 地面 Y 坐标（第一个非空气方块）
     */
    public int sampleGroundY(World world, int x, int z) {
        if (world == null) return 64;
        
        // 使用 Heightmap 获取最高非空气方块
        BlockPos pos = new BlockPos(x, 0, z);
        int topY = world.getTopY(Heightmap.Type.WORLD_SURFACE, pos);
        
        // 向下找第一个非空气（作为备用）
        BlockPos.Mutable m = new BlockPos.Mutable(x, topY, z);
        for (int y = topY; y > world.getBottomY() + 1; y--) {
            m.set(x, y, z);
            if (!world.getBlockState(m).isAir()) {
                return y;
            }
        }
        return world.getBottomY() + 1;
    }
}

