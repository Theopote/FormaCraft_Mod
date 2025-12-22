package com.formacraft.ai.prompt;

import com.formacraft.ai.context.OutlineContext;
import com.formacraft.ai.context.ProtectedZoneContext;
import com.formacraft.ai.context.SelectionContext;
import com.formacraft.ai.context.SemanticLabelContext;
import com.formacraft.ai.context.SymmetryContext;
import com.formacraft.client.interaction.AnchorState;
import net.minecraft.util.math.BlockPos;

/**
 * 工具状态 → PromptContext 语义构建器。
 * <p>
 * 这里不负责最终字符串格式，只负责把事实/约束分类塞进 ctx。
 */
public final class ToolPromptBuilder {
    private ToolPromptBuilder() {}

    public static void buildToolContext(PromptContext ctx) {
        if (ctx == null) return;

        // 锚点（基准点）
        BlockPos anchor = AnchorState.get();
        if (anchor != null) {
            ctx.annotations.add("Anchor: (" + anchor.getX() + "," + anchor.getY() + "," + anchor.getZ() + ")");
            ctx.constraints.add("Use the anchor as the spatial reference/origin for the build (place the main structure centered around the anchor unless other constraints say otherwise).");
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
            String t = line == null ? "" : line.trim();
            if (!t.isEmpty()) target.add(t);
        }
    }
}


