package com.formacraft.common.geometry.modifier;

import com.formacraft.common.geometry.GeometryModifier;
import com.formacraft.common.semantic.SemanticPlacementOp;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * OverhangModifier（出檐修饰器）
 * 
 * 将位置向上偏移，形成出檐效果
 * 
 * 应用场景：
 * - 中式屋檐
 * - 日式建筑
 * - 哥特挑檐
 */
public class OverhangModifier implements GeometryModifier {

    private final int offset;

    public OverhangModifier(int offset) {
        this.offset = Math.max(0, offset);
    }

    @Override
    public List<SemanticPlacementOp> apply(SemanticPlacementOp base) {
        if (offset == 0) {
            return List.of(base);
        }

        BlockPos p = base.pos().up(offset);
        return List.of(new SemanticPlacementOp(
                p,
                base.facing(),
                base.part(),
                base.role(),
                base.geometry(),
                base.tags()
        ));
    }
}

