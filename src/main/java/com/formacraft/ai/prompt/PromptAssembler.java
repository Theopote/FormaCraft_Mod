package com.formacraft.ai.prompt;

import com.formacraft.ai.context.SelectionContext;

/**
 * Prompt 拼接器：把用户自然语言增强为“可控、可解析”的结构化 Prompt。
 */
public final class PromptAssembler {
    private PromptAssembler() {}

    public static String assembleUserPrompt(String userInput) {
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

        ToolPromptBuilder.buildToolContext(ctx);

        var basePolicy = TerrainPolicyResolver.resolve(ctx);
        ctx.terrainPolicy = TerrainPolicyResolver.resolveFromUserText(raw, basePolicy);

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

        return buildFinalPrompt(ctx);
    }

    private static String buildFinalPrompt(PromptContext ctx) {
        StringBuilder sb = new StringBuilder(1024);

        sb.append(PromptSystemSections.systemRole());
        sb.append(PromptSystemSections.componentQuerySystemPrompt());
        sb.append(PromptSystemSections.socketSystemPrompt());

        MemoryContext memoryContext = PromptMemorySections.retrieveMemory(ctx);
        if (memoryContext != null && !memoryContext.isEmpty()) {
            sb.append(PromptMemorySections.memoryContextBlock(memoryContext));
        }

        sb.append(PromptSpatialSections.spatialConstraints(ctx));
        sb.append(PromptTemplateSections.userIntent(ctx));
        sb.append(PromptTemplateSections.structuredJsonTemplate(ctx));

        return sb.toString();
    }
}
