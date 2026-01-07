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
public class SkeletonPlan {
    public final SkeletonType type;
    public final BlockPos anchor;
    public final List<BlockPos> points; // optional
    public final Map<String, Object> params; // optional

    public SkeletonPlan(SkeletonType type, BlockPos anchor, List<BlockPos> points, Map<String, Object> params) {
        this.type = type;
        this.anchor = anchor;
        this.points = points;
        this.params = params;
    }

    /**
     * 获取整数参数
     */
    public int intParam(String key, int def) {
        Object v = params != null ? params.get(key) : null;
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s); } catch (Exception ignored) {}
        }
        return def;
    }

    /**
     * 获取浮点数参数
     */
    public double doubleParam(String key, double def) {
        Object v = params != null ? params.get(key) : null;
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s); } catch (Exception ignored) {}
        }
        return def;
    }

    /**
     * 获取字符串参数
     */
    public String strParam(String key, String def) {
        Object v = params != null ? params.get(key) : null;
        return v != null ? String.valueOf(v) : def;
    }
}
