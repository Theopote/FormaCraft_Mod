package com.formacraft.common.skeleton;

import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Map;

/**
 * SkeletonPlan: minimal input for generators.
 * 
 * LLM 输出的最小骨架数据结构
 * 
 * 字段：
 * - type: SkeletonType
 * - anchor: world origin
 * - points: for path/polyline/ring, etc. (world positions)
 * - params: numeric/string params (width, radius, height, etc.)
 */
public abstract class SkeletonPlan {
    public BlockPos anchor;
    public List<BlockPos> points; // optional
    public Map<String, Object> params; // optional

    public SkeletonPlan() {
        this.anchor = null;
        this.points = null;
        this.params = null;
    }

    public SkeletonPlan(BlockPos anchor, List<BlockPos> points, Map<String, Object> params) {
        this.anchor = anchor;
        this.points = points;
        this.params = params;
    }

    /**
     * 获取骨架类型（子类必须实现）
     */
    public abstract SkeletonType type();

    /**
     * 获取整数参数
     */
    public int intParam(String key, int def) {
        return SkeletonParamParsers.intParam(params, key, def);
    }

    /**
     * 获取浮点数参数
     */
    public double doubleParam(String key, double def) {
        return SkeletonParamParsers.doubleParam(params, key, def);
    }

    /**
     * 获取字符串参数
     */
    public String strParam(String key, String def) {
        Object v = params != null ? params.get(key) : null;
        return v != null ? String.valueOf(v) : def;
    }
}
