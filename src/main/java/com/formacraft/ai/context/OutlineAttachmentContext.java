package com.formacraft.ai.context;

import com.formacraft.client.tool.OutlineMode;
import com.formacraft.client.tool.OutlineTool;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * OutlineAttachmentContext：
 * 从 OutlineTool 的 footprint 轮廓中提取“候选附着面/拓扑位置”，用于语义放置。
 *
 * v1 输出：
 * - CORNER candidates：多边形顶点
 * - EDGE candidates：多边形边段（p[i] -> p[i+1]，最后闭合）
 *
 * 注意：
 * - 这是给 Prompt/LLM 的“候选集”，不是强制规则（强制过滤后续在 PatchFilter/生成器中接入）。
 * - 坐标只使用 X/Z（Y 由 OutlineShape.minY/maxY 提供范围）。
 */
public final class OutlineAttachmentContext {
    private OutlineAttachmentContext() {}

    public static String toPromptBlock() {
        if (!OutlineContext.hasOutline()) return "";
        OutlineTool.OutlineShape s = OutlineContext.shape();
        if (s == null) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("ATTACHMENT CANDIDATES (derived from Footprint Outline):\n");
        sb.append("- y_range: ").append(s.minY()).append("..").append(s.maxY()).append("\n");
        sb.append("- CORNER: place CORNER components near corners\n");
        sb.append("- EDGE: place EDGE components along edges (e.g. railings/guards)\n");

        if (s.mode() == OutlineMode.CIRCLE && s.center() != null) {
            BlockPos c = s.center();
            int r = Math.max(1, s.radius());
            sb.append("- shape: CIRCLE\n");
            sb.append("- center: (").append(c.getX()).append(",").append(c.getZ()).append(") radius=").append(r).append("\n");

            // 8 sample points on circumference as candidates (N,NE,E,SE,S,SW,W,NW)
            List<BlockPos> pts = new ArrayList<>(8);
            int rr = (int) Math.round(r / Math.sqrt(2.0));
            pts.add(new BlockPos(c.getX(), c.getY(), c.getZ() - r));      // N
            pts.add(new BlockPos(c.getX() + rr, c.getY(), c.getZ() - rr)); // NE
            pts.add(new BlockPos(c.getX() + r, c.getY(), c.getZ()));      // E
            pts.add(new BlockPos(c.getX() + rr, c.getY(), c.getZ() + rr)); // SE
            pts.add(new BlockPos(c.getX(), c.getY(), c.getZ() + r));      // S
            pts.add(new BlockPos(c.getX() - rr, c.getY(), c.getZ() + rr)); // SW
            pts.add(new BlockPos(c.getX() - r, c.getY(), c.getZ()));      // W
            pts.add(new BlockPos(c.getX() - rr, c.getY(), c.getZ() - rr)); // NW

            sb.append("- corners(sampled):\n");
            for (BlockPos p : pts) {
                sb.append("  - (").append(p.getX()).append(",").append(p.getZ()).append(")\n");
            }
            sb.append("- edges: circumference (continuous)\n");
            return sb.toString();
        }

        List<BlockPos> poly = s.points();
        if (poly == null || poly.size() < 3) return sb.toString();

        sb.append("- shape: POLYGON\n");
        sb.append("- corners:\n");
        for (BlockPos p : poly) {
            sb.append("  - (").append(p.getX()).append(",").append(p.getZ()).append(")\n");
        }
        sb.append("- edges:\n");
        for (int i = 0; i < poly.size(); i++) {
            BlockPos a = poly.get(i);
            BlockPos b = poly.get((i + 1) % poly.size());
            sb.append("  - ").append(i + 1).append(": (")
                    .append(a.getX()).append(",").append(a.getZ()).append(") -> (")
                    .append(b.getX()).append(",").append(b.getZ()).append(")\n");
        }
        return sb.toString();
    }
}

