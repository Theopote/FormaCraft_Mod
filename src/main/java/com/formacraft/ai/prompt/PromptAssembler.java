package com.formacraft.ai.prompt;

import com.formacraft.ai.context.SelectionContext;

/**
 * Prompt 拼接器：把用户自然语言增强为“可控、可解析”的结构化 Prompt。
 */
public final class PromptAssembler {
    private PromptAssembler() {}

    public static String assembleUserPrompt(String userInput) {
        // 兼容旧入口：默认 BUILD 模式
        return assemble(userInput, PromptMode.BUILD);
    }

    /**
     * 新入口：支持 PromptMode + PromptContext（工具→结构化上下文→最终 Prompt）。
     */
    public static String assemble(String userInput, PromptMode mode) {
        String raw = userInput == null ? "" : userInput.trim();
        if (raw.isEmpty()) return "";

        PromptContext ctx = new PromptContext();
        ctx.userMessage = raw;
        ctx.mode = mode == null ? PromptMode.BUILD : mode;

        // 1) 工具 → 结构化上下文
        ToolPromptBuilder.buildToolContext(ctx);

        // 2) 模式规则（预留：目前聊天建造仍走 BUILD）
        switch (ctx.mode) {
            case PATCH -> ctx.rules.add("- 仅输出增量修改（patch），不要重新生成整栋建筑");
            case MODIFY_REGION -> {
                if (SelectionContext.hasSelection()) {
                    ctx.rules.add("- 仅修改选区内的方块，保持选区外完全不变");
                } else {
                    ctx.rules.add("- 仅修改指定区域，保持其它区域不变");
                }
            }
            case BUILD -> {
                // no-op
            }
        }

        // 3) 拼装最终 Prompt
        return buildFinalPrompt(ctx);
    }

    private static String buildFinalPrompt(PromptContext ctx) {
        StringBuilder sb = new StringBuilder(768);

        sb.append("系统指令：\n");
        sb.append("- 你是一个 Minecraft 建筑师 AI，必须严格遵守所有规则/约束\n");
        sb.append("- 输出必须确定且可直接被程序反序列化\n\n");

        if (!ctx.rules.isEmpty()) {
            sb.append("Rules（硬规则）：\n");
            for (String r : ctx.rules) {
                sb.append("- ").append(r).append("\n");
            }
            sb.append("\n");
        }

        if (!ctx.constraints.isEmpty()) {
            sb.append("Constraints（约束）：\n");
            for (String c : ctx.constraints) {
                sb.append("- ").append(c).append("\n");
            }
            sb.append("\n");
        }

        if (!ctx.annotations.isEmpty()) {
            sb.append("Annotations（语义/标注）：\n");
            for (String a : ctx.annotations) {
                sb.append("- ").append(a).append("\n");
            }
            sb.append("\n");
        }

        sb.append("用户需求：\n");
        sb.append(ctx.userMessage).append("\n\n");

        // 输出要求（非常关键：保证可直接反序列化）
        sb.append("输出要求：\n");
        sb.append("- 输出严格合法的 JSON\n");
        sb.append("- 使用 FormaCraft BuildingSpec 结构\n");
        sb.append("- 不要输出任何解释性文字、前后缀、markdown 代码块\n");
        sb.append("- JSON 必须可直接反序列化\n");

        return sb.toString();
    }
}

