package com.formacraft.server.skeleton.gen;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.Random;

/**
 * 生成上下文：世界、origin、随机数、预算等
 */
public class GenerationContext {
    public final ServerWorld world;
    public final BlockPos origin;
    public final Random random;

    /** 安全预算：最大允许写入的方块数量，避免爆炸 */
    public final int maxOps;

    public GenerationContext(ServerWorld world, BlockPos origin, Random random, int maxOps) {
        this.world = world;
        this.origin = origin;
        this.random = random;
        this.maxOps = maxOps;
    }
    
    public GenerationContext(ServerWorld world, BlockPos origin, int maxOps) {
        this(world, origin, new Random(), maxOps);
    }
    
    /**
     * 查询某个 XZ 位置的地表高度
     * 
     * @param x X 坐标
     * @param z Z 坐标
     * @return 地表高度（Y 坐标）
     */
    public int getSurfaceY(int x, int z) {
        return world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
    }
    
    /**
     * 查询某个 XZ 位置的运动阻挡高度（更准确，排除树叶等）
     */
    public int getMotionBlockingY(int x, int z) {
        return world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
    }
}

