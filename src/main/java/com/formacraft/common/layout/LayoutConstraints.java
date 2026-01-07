package com.formacraft.common.layout;

import net.minecraft.util.math.BlockPos;

/**
 * LayoutConstraints（布局约束接口）
 * 
 * 核心功能：判断是否允许在某个位置放置站点
 * 
 * K2 会把禁区/轮廓/选区/语义标注接进来；先把接口摆好。
 */
public interface LayoutConstraints {

    /**
     * 是否允许在该位置放置（含禁区/保护区、轮廓裁切等）
     * 
     * @param anchor 站点锚点
     * @return true 如果允许放置
     */
    boolean allowAnchor(BlockPos anchor);

    /**
     * 是否允许某个 footprint 区域（站点占地范围）放置
     * 
     * v1 可以简单返回 true，或只检查中心点
     * 
     * @param anchor 站点锚点
     * @param footprintW 占地宽
     * @param footprintD 占地深
     * @param clearance 安全边距
     * @return true 如果允许放置
     */
    default boolean allowFootprint(BlockPos anchor, int footprintW, int footprintD, int clearance) {
        return allowAnchor(anchor);
    }

    /**
     * 允许所有位置的约束（默认）
     */
    LayoutConstraints ALLOW_ALL = a -> true;
}

