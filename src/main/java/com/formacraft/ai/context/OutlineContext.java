package com.formacraft.ai.context;

import com.formacraft.client.tool.OutlineMode;
import com.formacraft.client.tool.OutlineTool;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * 轮廓/Footprint 语义层：把 OutlineTool 的形状拼接进 Prompt。
 */
public final class OutlineContext {
    private OutlineContext() {}

    public static boolean hasOutline() {
        return OutlineTool.INSTANCE.hasShape();
    }

    public static OutlineTool.OutlineShape shape() {
        return OutlineTool.INSTANCE.getShape();
    }

    public static String toPromptBlock() {
        if (!hasOutline()) return "";
        OutlineTool.OutlineShape s = shape();
        if (s == null) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("轮廓/Footprint（强约束）：\n");
        sb.append("- 建筑必须严格位于该轮廓范围内，不得越界\n");
        sb.append("- 高度范围：Y=").append(s.minY()).append("~").append(s.maxY()).append("\n");

        if (s.mode() == OutlineMode.CIRCLE && s.center() != null) {
            sb.append("- 形状：圆形\n");
            sb.append("- 圆心：(").append(s.center().getX()).append(",").append(s.center().getZ()).append(")\n");
            sb.append("- 半径：").append(s.radius()).append("\n");
            return sb.toString();
        }

        List<BlockPos> pts = s.points();
        if (pts == null || pts.size() < 3) return sb.toString();
        sb.append("- 形状：多边形\n");
        sb.append("- 顶点（按顺序，X,Z）：\n");
        for (BlockPos p : pts) {
            sb.append("  - (").append(p.getX()).append(",").append(p.getZ()).append(")\n");
        }
        sb.append("- 绘制结束自动封闭（最后一段连接回起点）\n");
        return sb.toString();
    }
}


