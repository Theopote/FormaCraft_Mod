package com.formacraft.common.geometry.tool.nobuild;

import net.minecraft.util.math.BlockPos;

/**
 * NoBuildZone（禁区接口）
 * 
 * 定义任意形状的禁区
 * 这是"斜线遮罩"的本质逻辑层
 */
public interface NoBuildZone {
    /**
     * 判断位置是否在禁区内
     * 
     * @param pos 位置
     * @return true 如果在禁区内，false 如果不在
     */
    boolean contains(BlockPos pos);
}

