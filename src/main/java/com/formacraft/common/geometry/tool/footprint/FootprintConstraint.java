package com.formacraft.common.geometry.tool.footprint;

import com.formacraft.common.geometry.tool.GeometryConstraint;
import net.minecraft.util.math.BlockPos;

/**
 * FootprintConstraint（轮廓约束器）
 * 
 * 将 FootprintRegion 转换为 GeometryConstraint
 * 
 * 用途：
 * - "只在选区内建造"
 * - "在轮廓范围内生成寺庙"
 * - Patch 自动裁剪
 */
public class FootprintConstraint implements GeometryConstraint {

    private final FootprintRegion region;

    public FootprintConstraint(FootprintRegion region) {
        this.region = region;
    }

    @Override
    public boolean allow(BlockPos pos) {
        if (pos == null) return false;
        // 允许的位置 = 在轮廓内
        return region.contains(pos);
    }
}

