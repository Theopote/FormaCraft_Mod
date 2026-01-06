package com.formacraft.common.geometry.tool.footprint;

import net.minecraft.util.math.BlockPos;

/**
 * FootprintRegion（轮廓区域接口）
 * 
 * 定义建筑允许的轮廓范围
 * 
 * 这是 Selection → AI → Patch → Preview 闭环的核心安全阀
 */
public interface FootprintRegion {
    /**
     * 判断位置是否在轮廓内
     * 
     * @param pos 位置
     * @return true 如果在轮廓内，false 如果不在
     */
    boolean contains(BlockPos pos);
}

