package com.formacraft.common.patch.filter.impl;

import com.formacraft.common.model.constraint.ProtectedZone;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.filter.PatchFilter;
import com.formacraft.common.patch.filter.PatchFilterContext;

import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.stream.Collectors;

/**
 * ForbiddenZoneFilter（禁区裁剪）
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

        List<ProtectedZone> zones = context.snapshot.protectedZones;
        if (zones.isEmpty()) {
            return input;
        }

        return input.stream()
                .filter(p -> {
                    BlockPos world = origin.add(p.dx(), p.dy(), p.dz());
                    for (ProtectedZone zone : zones) {
                        if (zone != null && zone.contains(world)) {
                            return false;
                        }
                    }
                    return true;
                })
                .collect(Collectors.toList());
    }
}
