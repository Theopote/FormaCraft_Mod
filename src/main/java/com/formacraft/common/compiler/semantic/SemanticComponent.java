package com.formacraft.common.compiler.semantic;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Slot;

/**
 * SemanticComponent（语义构件）
 * 
 * 核心概念：LLM 输出的 component 经过编译后的语义表示
 * 
 * 包含：
 * - componentType - 构件类型（TOWER / KEEP / WALL / ROAD / GATE ...）
 * - slot - 所属 slot（路径 / 布局）
 * - source - 原始 LLM component
 */
public record SemanticComponent(
        /** 构件类型（TOWER / KEEP / WALL / ROAD / GATE ...） */
        String componentType,
        
        /** 所属 slot（路径 / 布局） */
        Slot slot,
        
        /** 原始 LLM component */
        Component source,
        
        /** 风格配置（可选，从 LlmPlan.styleProfile 传递） */
        String styleProfile
) {
    /**
     * 兼容旧代码的构造函数（styleProfile 为 null）
     */
    public SemanticComponent(String componentType, Slot slot, Component source) {
        this(componentType, slot, source, null);
    }
}

