package com.formacraft.ai.prompt;

import com.formacraft.ai.context.OutlineContext;
import com.formacraft.ai.context.ProtectedZoneContext;
import com.formacraft.ai.context.SelectionContext;
import com.formacraft.ai.context.SemanticLabelContext;
import com.formacraft.ai.context.SymmetryContext;
import com.formacraft.client.buildcontext.BuildContextResolver;
import com.formacraft.client.preview.PromptModeState;
import com.formacraft.common.buildcontext.BuildContext;

/**
 * 工具状态 → PromptContext 语义构建器。
 * <p>
 * 这里不负责最终字符串格式，只负责把事实/约束分类塞进 ctx。
 */
public final class ToolPromptBuilder {
    private ToolPromptBuilder() {}

    public static void buildToolContext(PromptContext ctx) {
        if (ctx == null) return;

        // 统一空间上下文（BuildContext）
        BuildContext bc = BuildContextResolver.resolve(PromptModeState.restrictToSelection());
        if (bc != null && bc.origin != null) {
            ctx.annotations.add("SpatialContext.mode: " + bc.mode.name());
            ctx.annotations.add("SpatialContext.origin: (" + bc.origin.getX() + "," + bc.origin.getY() + "," + bc.origin.getZ() + ")");
            ctx.annotations.add("SpatialContext.facing: " + (bc.facing != null ? bc.facing.name() : "UNKNOWN"));
            ctx.constraints.add("All coordinates and placement decisions should be made with respect to the given origin and facing.");
        }

        // 选区（边界约束）
        if (SelectionContext.hasSelection()) {
            addMultiline(ctx.constraints, SelectionContext.toPromptBlock());
            ctx.rules.add("- 建筑必须完全位于选区内，不得越界");
            ctx.rules.add("- 不要在选区外放置任何方块");
        }

        // 禁区/保护区（强规则）
        if (ProtectedZoneContext.hasZones()) {
            addMultiline(ctx.rules, ProtectedZoneContext.toPromptBlock());
        }

        // 轮廓/Footprint（强约束）
        if (OutlineContext.hasOutline()) {
            addMultiline(ctx.constraints, OutlineContext.toPromptBlock());
        }

        // 对称/镜像（约束）
        if (SymmetryContext.enabled()) {
            addMultiline(ctx.constraints, SymmetryContext.toPromptBlock());
        }

        // 语义标注（annotations）
        if (SemanticLabelContext.hasLabels()) {
            addMultiline(ctx.annotations, SemanticLabelContext.toPromptBlock());
        }
    }

    private static void addMultiline(java.util.List<String> target, String block) {
        if (target == null) return;
        if (block == null) return;
        String s = block.trim();
        if (s.isEmpty()) return;
        for (String line : s.split("\\R")) {
            String t = line.trim();
            if (!t.isEmpty()) target.add(t);
        }
    }
}


