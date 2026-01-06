package com.formacraft.common.geometry.tool.nobuild;

import com.formacraft.common.geometry.tool.GeometryConstraint;
import net.minecraft.util.math.BlockPos;

/**
 * NoBuildConstraint（禁区约束器）
 * 
 * 将 NoBuildZone 转换为 GeometryConstraint
 * 
 * 效果：
 * - AI 生成再疯狂，也碰不到禁区
 * - 河道 / 山体 / 保护遗迹
 */
public class NoBuildConstraint implements GeometryConstraint {

    private final NoBuildZone zone;

    public NoBuildConstraint(NoBuildZone zone) {
        this.zone = zone;
    }

    @Override
    public boolean allow(BlockPos pos) {
        if (pos == null) return false;
        // 允许的位置 = 不在禁区内
        return !zone.contains(pos);
    }
}

