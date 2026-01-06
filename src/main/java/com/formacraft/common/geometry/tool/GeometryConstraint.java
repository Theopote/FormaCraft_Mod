package com.formacraft.common.geometry.tool;

import net.minecraft.util.math.BlockPos;

/**
 * GeometryConstraint（几何约束）
 * 
 * Tool 不生成建筑
 * Tool 不决定风格
 * Tool 只"约束几何结果是否允许存在"
 * 
 * 它们是 Geometry Modifier 的"裁判"
 */
public interface GeometryConstraint {

    /**
     * 判断一个几何点是否允许存在
     * 
     * @param pos 位置
     * @return true 如果允许，false 如果不允许
     */
    boolean allow(BlockPos pos);
}

