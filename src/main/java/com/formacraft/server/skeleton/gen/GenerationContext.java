package com.formacraft.server.skeleton.gen;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

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
}

