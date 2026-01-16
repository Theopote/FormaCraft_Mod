package com.formacraft.common.debug;

import java.util.EnumSet;
import java.util.Set;

/**
 * DebugLayer（调试图层）
 * <p>
 * 定义所有可调试的可视化图层
 * <p>
 * 设计原则：
 * - 每个图层独立开关
 * - 图层按语义分组
 * - 颜色语义固定（避免混淆）
 */
public enum DebugLayer {
    // ========== PlanSkeleton 层（2D 语义）==========
    /** Plan Outline（AI / 用户给的平面轮廓） */
    PLAN_OUTLINE,

    /** Zone 区域（功能块） */
    PLAN_ZONES,

    /** 主轴 Axis */
    PLAN_AXIS_PRIMARY,

    /** 次轴 Axis */
    PLAN_AXIS_SECONDARY,

    // ========== StructuralSkeleton 层（3D 结构）==========
    /** 外墙 Baseline（外边界） */
    STRUCT_WALL_BASELINE_EXTERNAL,

    /** 庭院墙 Baseline（内孔边界） */
    STRUCT_WALL_BASELINE_COURTYARD,

    /** 内墙 Baseline（共享墙） */
    STRUCT_WALL_BASELINE_INTERNAL,

    /** Wall Solid（墙体体量） */
    STRUCT_WALL_SOLID,

    /** Courtyard Void（空洞） */
    STRUCT_COURTYARD_VOID,

    // ========== Skeleton 层（可执行骨架）==========
    /** Skeleton Bounds（骨架边界） */
    SKELETON_BOUNDS,

    /** Socket（Socket 点） */
    SKELETON_SOCKETS;

    /**
     * 获取 PlanSkeleton 层的所有图层
     */
    public static Set<DebugLayer> planLayers() {
        return EnumSet.of(
                PLAN_OUTLINE,
                PLAN_ZONES,
                PLAN_AXIS_PRIMARY,
                PLAN_AXIS_SECONDARY
        );
    }

    /**
     * 获取 StructuralSkeleton 层的所有图层
     */
    public static Set<DebugLayer> structuralLayers() {
        return EnumSet.of(
                STRUCT_WALL_BASELINE_EXTERNAL,
                STRUCT_WALL_BASELINE_COURTYARD,
                STRUCT_WALL_BASELINE_INTERNAL,
                STRUCT_WALL_SOLID,
                STRUCT_COURTYARD_VOID
        );
    }

    /**
     * 获取 Skeleton 层的所有图层
     */
    public static Set<DebugLayer> skeletonLayers() {
        return EnumSet.of(
                SKELETON_BOUNDS,
                SKELETON_SOCKETS
        );
    }

    /**
     * 获取所有图层
     */
    public static Set<DebugLayer> allLayers() {
        return EnumSet.allOf(DebugLayer.class);
    }
}
