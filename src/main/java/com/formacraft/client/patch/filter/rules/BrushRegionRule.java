package com.formacraft.client.patch.filter.rules;

import com.formacraft.ai.context.BrushContext;
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
        // 从 BrushContext 获取笔刷选中的位置
        if (BrushContext.hasBrushSelection()) {
            List<BlockPos> selected = BrushContext.getSelectedPositions();
            this.brushPositions = new LongOpenHashSet();
            for (BlockPos pos : selected) {
                if (pos != null) {
                    brushPositions.add(pos.asLong());
                }
            }
        } else {
            this.brushPositions = new LongOpenHashSet();
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

        // 检查该位置是否在笔刷选中的区域内
        // 笔刷选中的是地表一层方块，我们需要检查建筑的底层方块是否与笔刷区域重叠
        // 简化处理：检查 (x, z) 是否在笔刷区域内的任意一个 y 层
        for (long packed : brushPositions) {
            BlockPos brushPos = BlockPos.fromLong(packed);
            if (brushPos == null) continue;

            // 检查 XZ 平面是否重叠（允许 Y 方向扩展）
            if (brushPos.getX() == worldPos.getX() && brushPos.getZ() == worldPos.getZ()) {
                return true;
            }
        }

        // 如果位置不在任何笔刷选中方块上，拒绝
        return false;
    }

    @Override
    public String reason() {
        return "Patch outside brush-selected region";
    }
}
