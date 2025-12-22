package com.formacraft.ai.context;

import com.formacraft.client.tool.SemanticLabelTool;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * 区域语义标注语义层：把 SemanticLabelTool 的标签拼接进 Prompt。
 */
public final class SemanticLabelContext {
    private SemanticLabelContext() {}

    public static boolean hasLabels() {
        return SemanticLabelTool.INSTANCE.hasLabels();
    }

    public static List<SemanticLabelTool.AreaLabel> labels() {
        return SemanticLabelTool.INSTANCE.getLabels();
    }

    public static String toPromptBlock() {
        if (!hasLabels()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("区域语义标注：\n");
        sb.append("- 以下标签把自然语言意图与空间区域绑定，请严格遵守\n");
        int i = 1;
        for (SemanticLabelTool.AreaLabel l : labels()) {
            if (l == null || l.outline() == null || l.outline().size() < 3) continue;
            sb.append("  Area ").append(i++).append(" (").append(l.name()).append("):\n");
            sb.append("  - heightRange: Y=").append(l.minY()).append("~").append(l.maxY()).append("\n");
            sb.append("  - polygonVertices (X,Z):\n");
            for (BlockPos p : l.outline()) {
                sb.append("    - (").append(p.getX()).append(",").append(p.getZ()).append(")\n");
            }
        }
        return sb.toString();
    }
}


