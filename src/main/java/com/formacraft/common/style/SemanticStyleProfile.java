package com.formacraft.common.style;

import com.formacraft.common.geometry.GeometryModifier;
import com.formacraft.common.semantic.SemanticPart;

import java.util.EnumMap;
import java.util.Map;

/**
 * StyleProfile（建筑基因）
 * 
 * 目标：把《建筑风格全集》转成代码能用的对象
 * 可 JSON / 可代码 / 可热加载
 * 
 * 这是专门用于 SemanticPart 映射的 StyleProfile
 * 与现有的 StyleProfile（用于 BuildingSpec）不同
 * 
 * 同时支持：
 * - PaletteRule（材质映射）
 * - GeometryModifier（几何修饰）
 */
public class SemanticStyleProfile {

    private final String id;
    private final Map<SemanticPart, PaletteRule> paletteMap;
    private final Map<SemanticPart, GeometryModifier> geometryMap;

    public SemanticStyleProfile(String id) {
        this.id = id;
        this.paletteMap = new EnumMap<>(SemanticPart.class);
        this.geometryMap = new EnumMap<>(SemanticPart.class);
    }

    /**
     * 获取 ID
     */
    public String getId() {
        return id;
    }

    /**
     * 绑定 SemanticPart 到 PaletteRule（材质映射）
     */
    public SemanticStyleProfile bind(SemanticPart part, PaletteRule rule) {
        if (part != null && rule != null) {
            paletteMap.put(part, rule);
        }
        return this;
    }

    /**
     * 绑定 SemanticPart 到 GeometryModifier（几何修饰）
     */
    public SemanticStyleProfile bindGeometry(SemanticPart part, GeometryModifier modifier) {
        if (part != null && modifier != null) {
            geometryMap.put(part, modifier);
        }
        return this;
    }

    /**
     * 获取指定 SemanticPart 的 PaletteRule
     */
    public PaletteRule getRule(SemanticPart part) {
        return paletteMap.get(part);
    }

    /**
     * 获取指定 SemanticPart 的 GeometryModifier
     */
    public GeometryModifier getGeometry(SemanticPart part) {
        return geometryMap.get(part);
    }

    /**
     * 检查是否有指定 SemanticPart 的材质规则
     */
    public boolean hasRule(SemanticPart part) {
        return paletteMap.containsKey(part);
    }

    /**
     * 检查是否有指定 SemanticPart 的几何修饰
     */
    public boolean hasGeometry(SemanticPart part) {
        return geometryMap.containsKey(part);
    }
}

