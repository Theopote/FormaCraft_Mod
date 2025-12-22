package com.formacraft.common.patch.filter;

import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;

/**
 * Patch 规则上下文：提供 origin 等信息，便于规则计算绝对坐标。
 */
public class PatchRuleContext {
    public final BlockPos origin;

    /** 是否强制限制在选区内（通常对应 MODIFY_REGION）。 */
    public boolean restrictToSelection = false;

    public PatchRuleContext(BlockPos origin) {
        this.origin = origin != null ? origin : BlockPos.ORIGIN;
    }

    public BlockPos resolve(BlockPatch p) {
        if (p == null) return origin;
        return origin.add(p.dx(), p.dy(), p.dz());
    }
}


