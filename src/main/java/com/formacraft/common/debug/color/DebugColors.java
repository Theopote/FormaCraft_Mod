package com.formacraft.common.debug.color;

import java.awt.Color;

/**
 * DebugColors（调试颜色定义）
 * <p>
 * 统一管理所有调试图层的颜色
 * <p>
 * 颜色语义固定，避免混淆：
 * - Plan Outline: 蓝色 🟦
 * - Zone 区域: 半透明绿 🟩
 * - 主轴 Axis: 红线 🟥
 * - 次轴 Axis: 橙线 🟧
 * - 外墙 Baseline: 黄线 🟨
 * - 庭院 Baseline: 紫线 🟪
 * - Wall Solid: 半透明棕 🟫
 * - Courtyard Void: 黑色网格 ⬛
 * - Skeleton Bounds: 线框 🟦
 * - Socket: 点 / 小盒 🟠
 */
public final class DebugColors {

    private DebugColors() {}

    // ========== PlanSkeleton 层 ==========

    /** Plan Outline（蓝色 🟦） */
    public static final Color PLAN_OUTLINE = new Color(0x0066FF);

    /** Zone 区域（半透明绿 🟩） */
    public static final Color PLAN_ZONES = new Color(0x66FF66, false).brighter(); // 半透明效果（实际渲染时处理 alpha）

    /** 主轴 Axis（红线 🟥） */
    public static final Color PLAN_AXIS_PRIMARY = new Color(0xFF0000);

    /** 次轴 Axis（橙线 🟧） */
    public static final Color PLAN_AXIS_SECONDARY = new Color(0xFF8800);

    // ========== StructuralSkeleton 层 ==========

    /** 外墙 Baseline（黄线 🟨） */
    public static final Color STRUCT_WALL_BASELINE_EXTERNAL = new Color(0xFFFF00);

    /** 庭院墙 Baseline（紫线 🟪） */
    public static final Color STRUCT_WALL_BASELINE_COURTYARD = new Color(0xAA00FF);

    /** 内墙 Baseline（灰线） */
    public static final Color STRUCT_WALL_BASELINE_INTERNAL = new Color(0x888888);

    /** Wall Solid（半透明棕 🟫） */
    public static final Color STRUCT_WALL_SOLID = new Color(0x8B4513); // 半透明效果（实际渲染时处理 alpha）

    /** Courtyard Void（黑色网格 ⬛） */
    public static final Color STRUCT_COURTYARD_VOID = new Color(0x000000); // 半透明效果（实际渲染时处理 alpha）

    /** Roof Solid（半透明灰） */
    public static final Color STRUCT_ROOF = new Color(0xC0C0C0); // 半透明效果（实际渲染时处理 alpha）

    /** Roof Ridge（粗红线） */
    public static final Color STRUCT_ROOF_RIDGE = new Color(0xFF0000);

    /** Roof Slope（半透明斜面） */
    public static final Color STRUCT_ROOF_SLOPE = new Color(0xFFCC99); // 半透明效果（实际渲染时处理 alpha）

    // ========== Skeleton 层 ==========

    /** Skeleton Bounds（线框 🟦） */
    public static final Color SKELETON_BOUNDS = new Color(0x0066FF);

    /** Socket（点 / 小盒 🟠） */
    public static final Color SKELETON_SOCKETS = new Color(0xFF8800);

    /**
     * 获取图层对应的颜色
     */
    public static Color getColor(com.formacraft.common.debug.DebugLayer layer) {
        return switch (layer) {
            case PLAN_OUTLINE -> PLAN_OUTLINE;
            case PLAN_ZONES -> PLAN_ZONES;
            case PLAN_AXIS_PRIMARY -> PLAN_AXIS_PRIMARY;
            case PLAN_AXIS_SECONDARY -> PLAN_AXIS_SECONDARY;
            case STRUCT_WALL_BASELINE_EXTERNAL -> STRUCT_WALL_BASELINE_EXTERNAL;
            case STRUCT_WALL_BASELINE_COURTYARD -> STRUCT_WALL_BASELINE_COURTYARD;
            case STRUCT_WALL_BASELINE_INTERNAL -> STRUCT_WALL_BASELINE_INTERNAL;
            case STRUCT_WALL_SOLID -> STRUCT_WALL_SOLID;
            case STRUCT_COURTYARD_VOID -> STRUCT_COURTYARD_VOID;
            case STRUCT_ROOF -> STRUCT_ROOF;
            case STRUCT_ROOF_RIDGE -> STRUCT_ROOF_RIDGE;
            case STRUCT_ROOF_SLOPE -> STRUCT_ROOF_SLOPE;
            case SKELETON_BOUNDS -> SKELETON_BOUNDS;
            case SKELETON_SOCKETS -> SKELETON_SOCKETS;
        };
    }
}
