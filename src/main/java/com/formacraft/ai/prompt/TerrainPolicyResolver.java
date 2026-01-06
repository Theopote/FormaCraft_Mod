package com.formacraft.ai.prompt;

import com.formacraft.ai.context.OutlineContext;
import com.formacraft.ai.context.SelectionContext;
import com.formacraft.client.tool.PathTool;
import com.formacraft.common.terrain.TerrainPolicy;
import com.formacraft.common.terrain.TerrainStrategy;

/**
 * TerrainPolicyResolver（地形策略解析器）
 * 
 * 从工具状态计算 TerrainPolicy，塞进 PromptAssemblerInput。
 * 
 * v1 策略（推荐）：
 * - 如果有 PathTool 激活（且有路径）→ scope=PATH，strategy=ADAPTIVE
 * - else 如果有 OutlineTool → scope=OUTLINE，strategy=ADAPTIVE
 * - else 如果有 SelectionTool → scope=SELECTION，strategy=ADAPTIVE
 * - 否则（只有 anchor）→ scope=NONE，strategy=ADAPTIVE（但限制更严）
 */
public final class TerrainPolicyResolver {

    private TerrainPolicyResolver() {}

    /**
     * 从 PromptContext 解析地形策略
     * 
     * @param ctx Prompt 上下文
     * @return 地形策略
     */
    public static TerrainPolicy resolve(PromptContext ctx) {
        // 默认：ADAPTIVE + 严格限制
        TerrainPolicy.Builder b = TerrainPolicy.builder()
                .strategy(TerrainStrategy.ADAPTIVE) // 默认：ADAPTIVE，而不是 FLATTEN
                .scope(TerrainPolicy.Scope.NONE)
                .maxCutDepth(2)
                .maxFillHeight(2)
                .allowBridges(true)
                .allowStairs(true)
                .allowFoundations(true)
                .preserveOverallShape(true)
                .avoidLargeScaleFlatten(true); // 默认：避免大规模平整

        if (ctx == null) {
            return b.build();
        }

        // 优先级：PATH > OUTLINE > SELECTION > NONE

        // 1. 检查 PathTool（最高优先级）
        if (PathTool.INSTANCE != null) {
            var paths = PathTool.INSTANCE.getPaths();
            if (paths != null && !paths.isEmpty()) {
                // 有路径：使用 PATH 作用域，允许更大的调整
                // 强制规则：PATH_POLYLINE 时，FLATTEN 策略被禁止（除非用户明确）
                b.scope(TerrainPolicy.Scope.PATH)
                 .maxCutDepth(3)
                 .maxFillHeight(3)
                 .avoidLargeScaleFlatten(true); // 强制避免大规模平整
                return b.build();
            }
        }

        // 2. 检查 OutlineTool
        if (OutlineContext.hasOutline()) {
            b.scope(TerrainPolicy.Scope.OUTLINE)
             .maxCutDepth(3)
             .maxFillHeight(3);
            return b.build();
        }

        // 3. 检查 SelectionTool
        if (SelectionContext.hasSelection()) {
            b.scope(TerrainPolicy.Scope.SELECTION)
             .maxCutDepth(3)
             .maxFillHeight(3);
            return b.build();
        }

        // 4. 只有锚点：保持默认的严格限制
        return b.build();
    }

    /**
     * 从用户输入中解析地形策略（如果用户明确指定）
     * 
     * 例如：
     * - "完全平整地形" → FLATTEN
     * - "保护地形" → PRESERVE
     * - "梯田式" → TERRACE
     * 
     * @param userText 用户输入文本
     * @param basePolicy 基础策略（从工具状态解析）
     * @return 可能修改后的策略
     */
    public static TerrainPolicy resolveFromUserText(String userText, TerrainPolicy basePolicy) {
        if (userText == null || userText.trim().isEmpty()) {
            return basePolicy;
        }

        String lower = userText.toLowerCase();

        // 检查用户是否明确指定了策略
        if (lower.contains("完全平整") || lower.contains("平整地形") || 
            lower.contains("工业园区") || lower.contains("现代城市核心区")) {
            return TerrainPolicy.builder()
                    .strategy(TerrainStrategy.FLATTEN)
                    .scope(basePolicy.scope)
                    .maxCutDepth(10) // FLATTEN 允许更大的调整
                    .maxFillHeight(10)
                    .allowBridges(basePolicy.allowBridges)
                    .allowStairs(basePolicy.allowStairs)
                    .allowFoundations(basePolicy.allowFoundations)
                    .preserveOverallShape(false) // FLATTEN 不保护整体形状
                    .avoidLargeScaleFlatten(false) // FLATTEN 允许大规模平整
                    .build();
        }

        if (lower.contains("保护地形") || lower.contains("不削山") || 
            lower.contains("不填谷") || lower.contains("日式") || 
            lower.contains("精灵风") || lower.contains("山地寺庙")) {
            return TerrainPolicy.builder()
                    .strategy(TerrainStrategy.PRESERVE)
                    .scope(basePolicy.scope)
                    .maxCutDepth(0) // PRESERVE 不允许削填
                    .maxFillHeight(0)
                    .allowBridges(true)
                    .allowStairs(true)
                    .allowFoundations(false) // 只允许最小支撑
                    .preserveOverallShape(true)
                    .avoidLargeScaleFlatten(true)
                    .build();
        }

        if (lower.contains("梯田") || lower.contains("台地") || 
            lower.contains("山城") || lower.contains("中国古城") || 
            lower.contains("中世纪山地要塞")) {
            return TerrainPolicy.builder()
                    .strategy(TerrainStrategy.TERRACE)
                    .scope(basePolicy.scope)
                    .maxCutDepth(5) // TERRACE 允许中等调整
                    .maxFillHeight(5)
                    .allowBridges(true)
                    .allowStairs(true)
                    .allowFoundations(true)
                    .preserveOverallShape(true)
                    .avoidLargeScaleFlatten(true)
                    .build();
        }

        // 没有明确指定，返回基础策略
        return basePolicy;
    }
}

