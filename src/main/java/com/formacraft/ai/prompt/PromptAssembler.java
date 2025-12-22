package com.formacraft.ai.prompt;

import com.formacraft.ai.context.OutlineContext;
import com.formacraft.ai.context.ProtectedZoneContext;
import com.formacraft.ai.context.SelectionContext;
import com.formacraft.ai.context.SemanticLabelContext;
import com.formacraft.ai.context.SymmetryContext;

/**
 * Prompt 拼接器：把用户自然语言增强为“可控、可解析”的结构化 Prompt。
 */
public final class PromptAssembler {
    private PromptAssembler() {}

    public static String assembleUserPrompt(String userInput) {
        String raw = userInput == null ? "" : userInput.trim();
        if (raw.isEmpty()) return "";

        StringBuilder sb = new StringBuilder(512);

        // 1) 用户需求
        sb.append("用户需求：\n");
        sb.append(raw).append("\n\n");

        // 2) 选区约束（如果存在）
        if (SelectionContext.hasSelection()) {
            sb.append(SelectionContext.toPromptBlock()).append("\n");
            sb.append("约束补充：\n");
            sb.append("- 必须完全在该区域内生成，不得越界\n");
            sb.append("- 不要在区域外放置任何方块\n\n");
        }

        // 3) 禁区/保护区（强约束）
        if (ProtectedZoneContext.hasZones()) {
            sb.append(ProtectedZoneContext.toPromptBlock()).append("\n");
        }

        // 4) 轮廓/Footprint（强约束）
        if (OutlineContext.hasOutline()) {
            sb.append(OutlineContext.toPromptBlock()).append("\n");
        }

        // 5) 对称/镜像约束
        if (SymmetryContext.enabled()) {
            sb.append(SymmetryContext.toPromptBlock()).append("\n");
        }

        // 6) 区域语义标注
        if (SemanticLabelContext.hasLabels()) {
            sb.append(SemanticLabelContext.toPromptBlock()).append("\n");
        }

        // 7) 输出要求（非常关键：保证可直接反序列化）
        sb.append("输出要求：\n");
        sb.append("- 输出严格合法的 JSON\n");
        sb.append("- 使用 FormaCraft BuildingSpec 结构\n");
        sb.append("- 不要输出任何解释性文字、前后缀、markdown 代码块\n");
        sb.append("- JSON 必须可直接反序列化\n");

        return sb.toString();
    }
}

