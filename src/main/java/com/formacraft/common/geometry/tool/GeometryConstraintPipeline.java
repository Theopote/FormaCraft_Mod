package com.formacraft.common.geometry.tool;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * GeometryConstraintPipeline（几何约束管道）
 * 
 * 组合多个约束，所有约束都必须通过
 */
public class GeometryConstraintPipeline {

    private final List<GeometryConstraint> constraints = new ArrayList<>();

    /**
     * 添加约束
     */
    public void add(GeometryConstraint constraint) {
        if (constraint != null) {
            constraints.add(constraint);
        }
    }

    /**
     * 判断位置是否允许（所有约束都必须通过）
     */
    public boolean allow(BlockPos pos) {
        if (pos == null) return false;
        
        for (GeometryConstraint c : constraints) {
            if (!c.allow(pos)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 检查是否有约束
     */
    public boolean isEmpty() {
        return constraints.isEmpty();
    }

    /**
     * 清空所有约束
     */
    public void clear() {
        constraints.clear();
    }
}

