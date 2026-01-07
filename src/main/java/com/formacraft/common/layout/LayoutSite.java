package com.formacraft.common.layout;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * LayoutSite（布局站点）
 * 
 * 核心概念：位置 + facing + footprint
 * 
 * 用途：
 * - 道路、长城、办公楼组、沿路村落都靠这一层
 * - 供后续 Program/ComponentPreset 直接装配
 */
public class LayoutSite {
    public final BlockPos anchor;        // 站点锚点（世界坐标）
    public final Direction facing;        // 朝向（沿路径切线）
    public final int footprintW;           // 占地宽（X方向/侧向宽）
    public final int footprintD;          // 占地深（沿 facing 方向）
    public final int clearance;           // 额外安全边距（用于碰撞/避让）
    public final String tag;              // 可选：语义标签（如 "office", "tower", "gate"）

    public LayoutSite(BlockPos anchor, Direction facing, int footprintW, int footprintD, int clearance, String tag) {
        this.anchor = anchor;
        this.facing = facing;
        this.footprintW = footprintW;
        this.footprintD = footprintD;
        this.clearance = clearance;
        this.tag = tag;
    }

    /**
     * 创建带新 tag 的站点（不可变）
     */
    public LayoutSite withTag(String tag) {
        return new LayoutSite(anchor, facing, footprintW, footprintD, clearance, tag);
    }
}

