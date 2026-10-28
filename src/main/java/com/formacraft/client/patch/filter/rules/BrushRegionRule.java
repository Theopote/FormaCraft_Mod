package com.formacraft.client.patch.filter.rules;

import com.formacraft.client.ai.context.BrushContext;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.filter.PatchRule;
import com.formacraft.common.patch.filter.PatchRuleContext;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * 笔刷区域过滤规则：只保留在笔刷选中区域内的方块
 */
public final class BrushRegionRule implements PatchRule {
    private final LongOpenHashSet brushPositions;

    public BrushRegionRule() {
        this(BrushContext.getSelectedPositions());
    }

    public BrushRegionRule(List<BlockPos> selected) {
        brushPositions = new LongOpenHashSet();
        for (BlockPos pos : selected) {
            if (pos != null) brushPositions.add(BlockPos.asLong(pos.getX(), 0, pos.getZ()));
        }
    }

    @Override
    public boolean allow(BlockPatch patch, PatchRuleContext ctx) {
        if (patch == null || ctx == null) return false;
        
        // 如果笔刷为空，不限制（由其他规则决定）
        if (brushPositions.isEmpty()) {
            return true;
        }

        // 获取世界坐标
        BlockPos worldPos = ctx.resolve(patch);
        if (worldPos == null) {
            return false;
        }

        return brushPositions.contains(BlockPos.asLong(worldPos.getX(), 0, worldPos.getZ()));
    }

    @Override
    public String reason() {
        return "Patch outside brush-selected region";
    }
}
