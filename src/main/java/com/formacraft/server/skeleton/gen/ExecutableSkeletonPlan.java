package com.formacraft.server.skeleton.gen;

import com.formacraft.common.skeleton.SkeletonType;

import java.util.*;
import java.util.Objects;

/**
 * 可执行的 SkeletonPlan（用于 Generator 输入）
 * 
 * 这是从 LLM 输出或工具推导出来的 skeleton 计划。
 * v1 使用轻量 Map，后续可升级成强类型 record。
 * 
 * 注意：这与现有的 SkeletonPlan 接口不同，这是一个具体的数据类，
 * 用于接收 LLM 或工具的输入，然后传递给 Generator 执行。
 */
public class ExecutableSkeletonPlan {
    public final SkeletonType type;

    /** 参数表：例如 width, height, radius, points 等 */
    public final Map<String, Object> params = new HashMap<>();

    /** COMPOUND：子 skeleton 列表 */
    public final List<ExecutableSkeletonPlan> children = new ArrayList<>();

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
}

