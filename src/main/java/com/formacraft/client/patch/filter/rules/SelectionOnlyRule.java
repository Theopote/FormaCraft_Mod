package com.formacraft.client.patch.filter.rules;

import com.formacraft.common.buildcontext.BuildContext;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.filter.PatchRule;
import com.formacraft.common.patch.filter.PatchRuleContext;
import net.minecraft.util.math.BlockPos;

/**
 * 只允许修改选区内（用于 MODIFY_REGION / 增量安全模式）。
 */
public class SelectionOnlyRule implements PatchRule {
    private final BuildContext bc;

    public SelectionOnlyRule(BuildContext bc) {
        this.bc = bc;
    }

    @Override
    public boolean allow(BlockPatch patch, PatchRuleContext ctx) {
        if (patch == null || ctx == null) return false;
        if (bc == null || bc.selection == null) return true;
        BlockPos min = bc.selection.min();
        BlockPos max = bc.selection.max();
        if (min == null || max == null) return true;
        BlockPos p = ctx.resolve(patch);
        return p.getX() >= min.getX() && p.getX() <= max.getX()
                && p.getY() >= min.getY() && p.getY() <= max.getY()
                && p.getZ() >= min.getZ() && p.getZ() <= max.getZ();
    }

    @Override
    public String reason() {
        return "Patch outside selected region";
    }
}


