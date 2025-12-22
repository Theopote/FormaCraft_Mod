package com.formacraft.client.patch.filter.rules;

import com.formacraft.client.tool.ProtectedZoneTool;
import com.formacraft.common.model.constraint.ProtectedZone;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.filter.PatchRule;
import com.formacraft.common.patch.filter.PatchRuleContext;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * 禁区/保护区规则：禁止修改任何禁区内的方块。
 */
public class ProtectedZoneRule implements PatchRule {
    @Override
    public boolean allow(BlockPatch patch, PatchRuleContext ctx) {
        if (patch == null || ctx == null) return false;
        BlockPos pos = ctx.resolve(patch);
        List<ProtectedZone> zones = ProtectedZoneTool.INSTANCE.getZones();
        if (zones == null || zones.isEmpty()) return true;
        for (ProtectedZone z : zones) {
            if (z != null && z.contains(pos)) return false;
        }
        return true;
    }

    @Override
    public String reason() {
        return "Patch touches protected zone";
    }
}


