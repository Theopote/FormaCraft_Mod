package com.formacraft.common.mass.derived;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.List;

/**
 * MassDerivedSkeleton（从体量派生的骨架）
 * <p>
 * 🎯 核心定位（架构校准 2026-01-14）：
 * Skeleton 不是"体量的几何外壳"，
 * 而是"在体量规则发生变化的地方，生成的装配机会（装配界面）"。
 * <p>
 * 换句话说：
 * 哪里体量规则"发生断裂 / 变化 / 暴露"，
 * 哪里就派生 Skeleton / Socket。
 * <p>
 * 三大来源：
 * 1. 外暴露边界（Exterior Boundary）
 * 2. 内接触边界（Interface Boundary）
 * 3. 顶部边界（Top Boundary）
 */
public class MassDerivedSkeleton {
    /** Skeleton ID */
    public final String id;

    /** Skeleton 类型 */
    public final SkeletonKind kind;

    /** Skeleton 上下文 */
    public final SkeletonContext context;

    /** 方向（朝向） */
    public final Direction facing;

    /** Skeleton 覆盖的方块位置列表 */
    public final List<BlockPos> positions;

    /** 高度区间（最小 Y，最大 Y） */
    public final int minY;
    public final int maxY;

    public MassDerivedSkeleton(
            String id,
            SkeletonKind kind,
            SkeletonContext context,
            Direction facing,
            List<BlockPos> positions,
            int minY,
            int maxY
    ) {
        this.id = id;
        this.kind = kind;
        this.context = context;
        this.facing = facing;
        this.positions = positions != null ? List.copyOf(positions) : List.of();
        this.minY = minY;
        this.maxY = maxY;
    }

    /**
     * Skeleton 类型
     */
    public enum SkeletonKind {
        /** 墙 / 立面 */
        WALL,
        /** 立面（外立面） */
        FACADE,
        /** 地面 / 楼板 */
        FLOOR,
        /** 天花板 */
        CEILING,
        /** 屋顶 / 平台 */
        ROOF,
        /** 露台 */
        TERRACE
    }

    /**
     * Skeleton 上下文
     */
    public enum SkeletonContext {
        /** 外部 */
        EXTERIOR,
        /** 内部 */
        INTERIOR,
        /** 连接 */
        CONNECTION
    }
}
