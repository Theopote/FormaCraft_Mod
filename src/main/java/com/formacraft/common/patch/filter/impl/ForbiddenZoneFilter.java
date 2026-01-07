package com.formacraft.common.patch.filter.impl;

import com.formacraft.client.tool.ProtectedZoneTool;
import com.formacraft.common.model.constraint.ProtectedZone;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.filter.PatchFilter;
import com.formacraft.common.patch.filter.PatchFilterContext;

import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.stream.Collectors;

/**
 * ForbiddenZoneFilter（禁区裁剪）
 * 
 * 核心功能：移除所有位于禁区内的 BlockPatch
 * 
 * 效果：
 * 🟥 AI 想在禁区放什么都直接消失
 */
public class ForbiddenZoneFilter implements PatchFilter {

    @Override
    public List<BlockPatch> filter(
            List<BlockPatch> input,
            BlockPos origin,
            PatchFilterContext context
    ) {
        if (!context.hasForbiddenZone()) {
            return input;
        }

        ProtectedZoneTool tool = context.forbidden;
        List<ProtectedZone> zones = tool.getZones();

        if (zones == null || zones.isEmpty()) {
            return input;
        }

        return input.stream()
                .filter(p -> {
                    BlockPos world = origin.add(p.dx(), p.dy(), p.dz());
                    // 检查是否在任何禁区内
                    for (ProtectedZone zone : zones) {
                        if (zone != null && zone.contains(world)) {
                            return false; // 在禁区内，移除
                        }
                    }
                    return true; // 不在禁区内，保留
                })
                .collect(Collectors.toList());
    }
}

