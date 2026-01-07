package com.formacraft.common.cluster.zoning;

import com.formacraft.common.semantic.ComponentPreset;
import com.formacraft.common.semantic.SemanticComponentType;

/**
 * ProgramPresetResolver（程序预设解析器）
 * 
 * K3.1 核心：支持风格偏置的预设解析器
 * 
 * 可以根据 styleId 对默认预设进行微调，实现不同风格下的差异化效果
 */
public final class ProgramPresetResolver {

    private ProgramPresetResolver() {}

    /**
     * 解析预设（支持风格偏置）
     * 
     * @param styleId 风格 ID（可为 null）
     * @param program 建筑功能
     * @return 组件预设
     */
    public static ComponentPreset resolve(String styleId, BuildingProgram program) {
        ComponentPreset base = ProgramPresetLibrary.getDefault(program);

        // 简单风格偏置（后续可以换成 StyleProfile 数据驱动）
        if (styleId == null || styleId.isBlank()) {
            return base;
        }

        String s = styleId.toLowerCase();
        ComponentPreset result = base;

        if (s.contains("cyber") || s.contains("cyberpunk")) {
            // 赛博朋克：招牌/灯更多
            result = tweak(result, SemanticComponentType.SIGNAGE, 0.15f, 0.0f);
            result = tweak(result, SemanticComponentType.STREET_LIGHTS, 0.0f, 0.10f);
        } else if (s.contains("medieval") || s.contains("castle")) {
            // 中世纪：围墙更常见
            result = tweak(result, SemanticComponentType.FENCE_OR_WALL, 0.10f, 0.0f);
        } else if (s.contains("modern") || s.contains("contemporary")) {
            // 现代：更简洁，减少装饰
            result = tweak(result, SemanticComponentType.SIGNAGE, -0.10f, 0.0f);
            result = tweak(result, SemanticComponentType.BALCONY, 0.10f, 0.0f);
        } else if (s.contains("classical") || s.contains("traditional")) {
            // 古典：更强调入口和装饰
            result = tweak(result, SemanticComponentType.ENTRANCE, 0.10f, 0.0f);
            result = tweak(result, SemanticComponentType.PLAZA_CORE, 0.15f, 0.0f);
        }

        return result;
    }

    /**
     * 微调预设（复制一份并调整）
     */
    private static ComponentPreset tweak(ComponentPreset src, SemanticComponentType type, float weightDelta, float densityDelta) {
        ComponentPreset copy = new ComponentPreset(src.id, src.descriptionForPrompt);
        for (var it : src.items) {
            if (it.type() == type) {
                float w = clamp01(it.weight() + weightDelta);
                float d = clamp01(it.density() + densityDelta);
                copy.add(new ComponentPreset.Item(it.type(), w, d, it.minSize(), it.maxSize(), it.noteForPrompt()));
            } else {
                copy.add(it);
            }
        }
        return copy;
    }

    /**
     * 将值限制在 [0, 1] 范围内
     */
    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}

