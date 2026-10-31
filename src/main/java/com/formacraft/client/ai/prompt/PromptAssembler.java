package com.formacraft.client.ai.prompt;

import com.formacraft.client.ai.context.SelectionContext;

/**
 * Offline/debug Prompt 拼接器（<b>生产路径已弃用</b>）。
 * <p>
 * Chat build 的 system prompt 由 Python {@code llm_plan_system_prompt} 拥有；
 * 客户端只发 {@code userMessage} + 结构化约束。勿在 C2S 请求中再注入本类输出。
 * 保留供单元测试与本地调试；新能力请加到后端 prompt_assembly / llm_plan_system_prompt。
 *
 * @deprecated Production builds must not call this; use backend-owned prompts.
 */
@Deprecated
public final class PromptAssembler {

    private static final boolean ASSEMBLY_HARD_MANDATE =
            Boolean.getBoolean("formacraft.prompt.assemblyHardMandate");

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

        boolean assemblyIntent = AssemblyIntentSections.detectsFreeformAssemblyIntent(ctx.userMessage);
        // Freeform requests must not inherit instructions to select a fixed landmark.
        if (!assemblyIntent) {
            sb.append(PromptSystemSections.landmarkModulesPrompt());
        }

        sb.append(com.formacraft.common.archetype.LandmarkRoutingPolicy.promptVariationPrinciples());

        // Proportion ontology: cottage / castle / stadium typology ratios
        if (!assemblyIntent) {
            String proportionHint = com.formacraft.common.proportion.ProportionCardRegistry
                    .promptBlockForIntent(ctx.userMessage, null);
            if (proportionHint != null && !proportionHint.isBlank()) {
                sb.append(proportionHint);
            }
        }

        if (!assemblyIntent) {
            String landmarkHint = com.formacraft.common.archetype.LandmarkModuleRegistry
                    .promptRoutingHintForIntent(ctx.userMessage);
            if (landmarkHint != null && !landmarkHint.isBlank()) {
                sb.append(landmarkHint);
            }
        }

        String assemblyHint = AssemblyIntentSections.promptBlockForIntent(ctx.userMessage);
        if (assemblyHint != null && !assemblyHint.isBlank()) {
            sb.append(assemblyHint);
            if (ASSEMBLY_HARD_MANDATE) {
                ctx.rules.add("- 本请求必须使用 component_type ASSEMBLY 表达主体量，禁止把 params.assembly 嵌在 MASS_* 内");
            } else {
                ctx.rules.add("- 若自由形体/组合体量更适合 ASSEMBLY，优先使用 component_type ASSEMBLY（非强制）");
            }
        }

        String assemblySchema = AssemblySchemaCatalog.promptBlockForIntent(ctx.userMessage);
        if (assemblySchema != null && !assemblySchema.isBlank()) {
            sb.append(assemblySchema);
        }

        // Prompt 瘦身：ComponentQuery / Socket 两大系统仅在存在玩家构件库时才有意义。
        // 无库时这两段（约 5-6k 字符）纯属浪费 token，直接跳过，让 LLM 用语义组件搭建。
        if (PromptSpatialSections.hasComponentLibrary()) {
            sb.append(PromptSystemSections.componentQuerySystemPrompt());
            sb.append(PromptSystemSections.socketSystemPrompt());
        }

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
