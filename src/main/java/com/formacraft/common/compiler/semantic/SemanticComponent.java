package com.formacraft.common.compiler.semantic;

import com.formacraft.common.genome.BuildingGenome;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.llm.dto.StyleAttributes;

/**
 * SemanticComponent（语义构件）
 * 
 * 核心概念：LLM 输出的 component 经过编译后的语义表示
 * 
 * 包含：
 * - componentType - 构件类型（TOWER / KEEP / WALL / ROAD / GATE ...）
 * - slot - 所属 slot（路径 / 布局）
 * - source - 原始 LLM component
 * - styleProfile - 风格配置（可选，从 LlmPlan.styleProfile 传递）
 * - styleAttributes - 风格属性（可选，从 LlmPlan.styleAttributes 传递，用于动态材质选择）
 * - genome - BuildingGenome（可选，从 LlmPlan.genome 传递，用于参数化生成）
 */
public record SemanticComponent(
        /** 构件类型（TOWER / KEEP / WALL / ROAD / GATE ...） */
        String componentType,
        
        /** 所属 slot（路径 / 布局） */
        Slot slot,
        
        /** 原始 LLM component */
        Component source,
        
        /** 风格配置（可选，从 LlmPlan.styleProfile 传递） */
        String styleProfile,
        
        /** 风格属性（可选，从 LlmPlan.styleAttributes 传递，用于动态材质选择） */
        StyleAttributes styleAttributes,

        /** BuildingGenome（可选，从 LlmPlan.genome 传递，用于参数化生成） */
        BuildingGenome genome
) {
    /**
     * 兼容旧代码的构造函数（styleProfile 和 styleAttributes 为 null）
     */
    public SemanticComponent(String componentType, Slot slot, Component source) {
        this(componentType, slot, source, null, null, null);
    }
    
    /**
     * 兼容旧代码的构造函数（styleAttributes 为 null）
     */
    public SemanticComponent(String componentType, Slot slot, Component source, String styleProfile) {
        this(componentType, slot, source, styleProfile, null, null);
    }

    /**
     * 兼容旧代码的构造函数（genome 为 null）
     */
    public SemanticComponent(String componentType, Slot slot, Component source, String styleProfile, StyleAttributes styleAttributes) {
        this(componentType, slot, source, styleProfile, styleAttributes, null);
    }
}

