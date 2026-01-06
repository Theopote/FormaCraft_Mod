package com.formacraft.server.skeleton.gen;

import com.formacraft.common.skeleton.SkeletonType;
import net.minecraft.util.math.Direction;

import java.util.*;
import java.util.Objects;

/**
 * 可执行的 SkeletonPlan（用于 Generator 输入）
 * 
 * 这是从 LLM 输出或工具推导出来的 skeleton 计划。
 * 
 * 核心设计原则：
 * - Skeleton 只决定"结构拓扑"（朝向、中心线、宽度、高度趋势）
 * - ❌ 不决定具体方块
 * - ❌ 不关心风格
 * - ❌ 不关心装饰
 * 
 * 这些都应该在 Generator + Palette + Tool 约束层完成。
 */
public class ExecutableSkeletonPlan {
    public final SkeletonType type;

    /** 参数表：例如 width, height, radius, points 等 */
    public final Map<String, Object> params = new HashMap<>();

    /** COMPOUND：子 skeleton 列表 */
    public final List<ExecutableSkeletonPlan> children = new ArrayList<>();

    // ========== 核心增强字段 ==========
    
    /** 基础几何：长度 */
    public int length = 10;
    
    /** 基础几何：高度 */
    public int height = 5;
    
    /** 横向宽度（单位：block） */
    public int width = 1;
    
    /** 朝向 */
    public Direction facing = Direction.NORTH;
    
    /** 是否贴地/顺地形 */
    public boolean conformTerrain = true;
    
    /** 高度策略 */
    public HeightPolicy heightPolicy = HeightPolicy.FLAT;
    
    /**
     * 高度策略枚举
     */
    public enum HeightPolicy {
        /** 完全平 */
        FLAT,
        /** 顺地形 */
        FOLLOW_TERRAIN,
        /** 台阶 */
        STEP_UP,
        /** 坡道 */
        SLOPE
    }

    public ExecutableSkeletonPlan(SkeletonType type) {
        this.type = Objects.requireNonNull(type);
    }

    public ExecutableSkeletonPlan put(String key, Object value) {
        params.put(key, value);
        return this;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, T defaultValue) {
        Object v = params.get(key);
        if (v == null) return defaultValue;
        try { 
            return (T) v; 
        } catch (ClassCastException e) { 
            return defaultValue; 
        }
    }

    public ExecutableSkeletonPlan addChild(ExecutableSkeletonPlan child) {
        if (child != null) {
            children.add(child);
        }
        return this;
    }
    
    // ========== 便捷方法：从 params 读取并设置字段 ==========
    
    /**
     * 从 params 中读取并设置所有字段（用于从 LLM 输出解析）
     */
    public ExecutableSkeletonPlan applyParams() {
        // 基础几何
        if (params.containsKey("length")) {
            Object v = params.get("length");
            this.length = toInt(v, this.length);
        }
        if (params.containsKey("height")) {
            Object v = params.get("height");
            this.height = toInt(v, this.height);
        }
        if (params.containsKey("width")) {
            Object v = params.get("width");
            this.width = toInt(v, this.width);
        }
        
        // 朝向
        if (params.containsKey("facing")) {
            Object v = params.get("facing");
            if (v instanceof Direction d) {
                this.facing = d;
            } else if (v instanceof String s) {
                try {
                    this.facing = Direction.valueOf(s.toUpperCase());
                } catch (IllegalArgumentException e) {
                    // 保持默认值
                }
            }
        }
        
        // 地形适应
        if (params.containsKey("conformTerrain")) {
            Object v = params.get("conformTerrain");
            if (v instanceof Boolean b) {
                this.conformTerrain = b;
            } else if (v instanceof String s) {
                this.conformTerrain = Boolean.parseBoolean(s);
            }
        }
        
        // 高度策略
        if (params.containsKey("heightPolicy")) {
            Object v = params.get("heightPolicy");
            if (v instanceof HeightPolicy hp) {
                this.heightPolicy = hp;
            } else if (v instanceof String s) {
                try {
                    this.heightPolicy = HeightPolicy.valueOf(s.toUpperCase());
                } catch (IllegalArgumentException e) {
                    // 保持默认值
                }
            }
        }
        
        return this;
    }
    
    private static int toInt(Object v, int def) {
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return def; }
    }
}

