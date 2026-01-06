package com.formacraft.common.component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 组件参数
 * 
 * 每个组件都有尺寸 / 相对位置 / 约束 / tags
 */
public class ComponentSpec {

    public final ComponentType type;

    /** 相对 Skeleton 原点的偏移（不是绝对坐标） */
    public int offsetX = 0;
    public int offsetY = 0;
    public int offsetZ = 0;

    /** 组件尺寸（不同组件解释不同） */
    public int width = 0;
    public int depth = 0;
    public int height = 0;

    /** 行为参数（如 ringRadius / levels / thickness） */
    public Map<String, Object> params = new HashMap<>();

    /** 语义标签（如 defensive / ceremonial / inner） */
    public Set<String> tags = new HashSet<>();

    public ComponentSpec(ComponentType type) {
        this.type = type;
    }

    /**
     * 便捷方法：设置偏移
     */
    public ComponentSpec offset(int x, int y, int z) {
        this.offsetX = x;
        this.offsetY = y;
        this.offsetZ = z;
        return this;
    }

    /**
     * 便捷方法：设置尺寸
     */
    public ComponentSpec size(int width, int depth, int height) {
        this.width = width;
        this.depth = depth;
        this.height = height;
        return this;
    }

    /**
     * 便捷方法：添加参数
     */
    public ComponentSpec param(String key, Object value) {
        this.params.put(key, value);
        return this;
    }

    /**
     * 便捷方法：添加标签
     */
    public ComponentSpec tag(String tag) {
        this.tags.add(tag);
        return this;
    }
}

