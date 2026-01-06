package com.formacraft.common.component;

import java.util.ArrayList;
import java.util.List;

/**
 * 组件图（Component Graph）
 * 
 * 一次建筑的组件图（由 LLM 生成或规则补全）
 */
public class ComponentPlan {

    public final List<ComponentSpec> components = new ArrayList<>();

    /**
     * 添加组件
     */
    public ComponentPlan add(ComponentSpec spec) {
        if (spec != null) {
            components.add(spec);
        }
        return this;
    }

    /**
     * 添加多个组件
     */
    public ComponentPlan addAll(List<ComponentSpec> specs) {
        if (specs != null) {
            components.addAll(specs);
        }
        return this;
    }

    /**
     * 获取组件列表
     */
    public List<ComponentSpec> getComponents() {
        return components;
    }

    /**
     * 是否为空
     */
    public boolean isEmpty() {
        return components.isEmpty();
    }
}

