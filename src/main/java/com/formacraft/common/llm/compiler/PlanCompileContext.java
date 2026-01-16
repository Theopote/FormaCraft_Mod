package com.formacraft.common.llm.compiler;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * PlanCompileContext（编译上下文）
 * <p>
 * 核心思想：AI 不决定"怎么高"，系统策略决定。
 * <p>
 * 这非常重要，避免 LLM 失控。
 * <p>
 * 包含：
 * - terrain: 地形上下文
 * - heightStrategy: 高度策略
 * - roofStrategy: 屋顶策略
 * - 默认值：墙高、地板厚度等
 */
public class PlanCompileContext {
    /** 地形上下文（可选） */
    public final TerrainContext terrain;

    /** 高度策略 */
    public final HeightStrategy heightStrategy;

    /** 屋顶策略 */
    public final RoofStrategy roofStrategy;

    /** 默认墙高 */
    public final double defaultWallHeight;

    /** 默认地板厚度 */
    public final double defaultFloorThickness;

    public PlanCompileContext(
            TerrainContext terrain,
            HeightStrategy heightStrategy,
            RoofStrategy roofStrategy,
            double defaultWallHeight,
            double defaultFloorThickness
    ) {
        this.terrain = terrain;
        this.heightStrategy = heightStrategy != null ? heightStrategy : HeightStrategy.FLAT;
        this.roofStrategy = roofStrategy != null ? roofStrategy : RoofStrategy.UNIFIED;
        this.defaultWallHeight = defaultWallHeight > 0 ? defaultWallHeight : 5.0;
        this.defaultFloorThickness = defaultFloorThickness > 0 ? defaultFloorThickness : 1.0;
    }

    /**
     * 创建默认上下文
     */
    public static PlanCompileContext createDefault() {
        return new PlanCompileContext(
                null,  // 无地形上下文
                HeightStrategy.FLAT,
                RoofStrategy.UNIFIED,
                5.0,   // 默认墙高
                1.0    // 默认地板厚度
        );
    }

    /**
     * 创建带地形的上下文
     */
    public static PlanCompileContext createWithTerrain(ServerWorld world, BlockPos origin) {
        return new PlanCompileContext(
                new TerrainContext(world, origin),
                HeightStrategy.ADAPTIVE,
                RoofStrategy.UNIFIED,
                5.0,
                1.0
        );
    }

    /**
     * 高度策略
     */
    public enum HeightStrategy {
        FLAT,       // 完全平
        ADAPTIVE,   // 自适应地形
        STEPPED,    // 台阶式
        SLOPED      // 斜坡式
    }

    /**
     * 屋顶策略
     */
    public enum RoofStrategy {
        UNIFIED,        // 统一屋顶（v1 默认）
        PARTITIONED,    // 分区屋顶（未来）
        VARIED          // 高低错动（未来）
    }

    /**
     * 地形上下文
     */
    public static class TerrainContext {
        public final ServerWorld world;
        public final BlockPos origin;

        public TerrainContext(ServerWorld world, BlockPos origin) {
            this.world = world;
            this.origin = origin;
        }
    }
}
