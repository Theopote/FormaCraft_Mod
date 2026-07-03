package com.formacraft.server.build;

import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.model.request.FormaRequest;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端生成阶段硬裁剪：
 * - 禁区/保护区：永远不能被修改/放置
 * - 轮廓（Outline）：只能在轮廓内生成
 * - 选区（Selection）：如果请求里带了 selectionMin/Max，则只能在选区内生成
 *
 * 目的：把“工具约束”从纯提示/patch 过滤升级为“生成阶段确定性裁剪”，避免依赖 AI 自觉。
 */
public final class BuildConstraintClipper {
    private BuildConstraintClipper() {}

    public static List<PlannedBlock> clipPlannedBlocks(List<PlannedBlock> blocks, FormaRequest req) {
        if (blocks == null || blocks.isEmpty()) return blocks;
        if (req == null) return blocks;

        BuildConstraints c = BuildConstraintContext.withRequest(req, BuildConstraintContext::current);
        if (c == null) return blocks;
        boolean hasSelection = c.selectionMin != null && c.selectionMax != null;
        boolean hasOutline = c.outline != null;
        boolean hasZones = c.protectedZones != null && !c.protectedZones.isEmpty();
        boolean hasBrush = c.brushMin != null && c.brushMax != null;
        boolean hasPath = c.pathNodes != null && c.pathNodes.size() >= 2 && c.pathRadius > 0;
        if (!hasSelection && !hasOutline && !hasZones && !hasBrush && !hasPath) return blocks;

        List<PlannedBlock> out = new ArrayList<>(blocks.size());
        for (PlannedBlock pb : blocks) {
            if (pb == null) continue;
            BlockPos p = pb.getPos();
            if (p == null) continue;

            // single allow() check (same semantics as client OutlineRule + protected zones)
            if (!c.allow(p)) continue;

            out.add(pb);
        }
        return out;
    }
}


