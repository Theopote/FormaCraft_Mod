package com.formacraft.common.semantic;

import java.util.ArrayList;
import java.util.List;

/**
 * ComponentPreset（组件装配清单）
 * 
 * K3.1 核心：一套完整的组件装配清单，包含权重、密度、尺寸建议
 * 
 * 用于将 BuildingProgram 转换为具体的组件装配指令
 */
public final class ComponentPreset {

    public final String id;
    public final String descriptionForPrompt;
    public final List<Item> items = new ArrayList<>();

    public ComponentPreset(String id, String descriptionForPrompt) {
        this.id = id != null ? id : "DEFAULT";
        this.descriptionForPrompt = descriptionForPrompt != null ? descriptionForPrompt : "";
    }

    /**
     * 添加组件项
     */
    public ComponentPreset add(Item item) {
        if (item != null) {
            items.add(item);
        }
        return this;
    }

    /**
     * 组件项（带权重+密度+尺寸建议）
     */
    public record Item(
            /** 组件类型 */
            SemanticComponentType type,
            
            /** 装配权重（0~1）：越大越必做 */
            float weight,
            
            /** 密度（0~1）：路灯/长椅这类用 */
            float density,
            
            /** 组件最小尺度（方块） */
            int minSize,
            
            /** 组件最大尺度（方块） */
            int maxSize,
            
            /** 额外提示（例如"沿街侧优先"） */
            String noteForPrompt
    ) {
        public Item {
            // 验证参数
            weight = Math.max(0.0f, Math.min(1.0f, weight));
            density = Math.max(0.0f, Math.min(1.0f, density));
            minSize = Math.max(1, minSize);
            maxSize = Math.max(minSize, maxSize);
            noteForPrompt = noteForPrompt != null ? noteForPrompt : "";
        }
    }

    /**
     * 转换为 Prompt 文本
     */
    public String toPromptText() {
        StringBuilder sb = new StringBuilder();
        sb.append("preset_id=").append(id).append("\n");
        sb.append(descriptionForPrompt).append("\n");
        for (Item it : items) {
            sb.append("- ")
              .append(it.type()).append(" ")
              .append("(weight=").append(trim(it.weight()))
              .append(", density=").append(trim(it.density()))
              .append(", size=").append(it.minSize()).append("~").append(it.maxSize())
              .append(") ");
            if (it.noteForPrompt() != null && !it.noteForPrompt().isBlank()) {
                sb.append("note=").append(it.noteForPrompt());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static String trim(float v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}

