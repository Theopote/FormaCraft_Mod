package com.formacraft.common.geometry.modifier;

import com.formacraft.common.geometry.GeometryModifier;
import com.formacraft.common.semantic.SemanticPlacementOp;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * ThickWallModifier（厚墙修饰器）
 * 
 * 将单格墙扩展为多格厚墙
 * 
 * 应用场景：
 * - 中世纪城墙
 * - 要塞
 * - 金字塔
 */
public class ThickWallModifier implements GeometryModifier {

    private final int thickness;

    public ThickWallModifier(int thickness) {
        this.thickness = Math.max(1, thickness);
    }

    @Override
    public List<SemanticPlacementOp> apply(SemanticPlacementOp base) {
        List<SemanticPlacementOp> result = new ArrayList<>();
        
        // 添加原始位置
        result.add(base);

        if (thickness <= 1) {
            return result;
        }

        // 获取垂直于墙面的方向（向右）
        Direction normal = base.facing().rotateYClockwise();

        // 向右侧扩展
        for (int i = 1; i < thickness; i++) {
            BlockPos p = base.pos().offset(normal, i);
            result.add(new SemanticPlacementOp(
                    p,
                    base.facing(),
                    base.part(),
                    base.role(),
                    base.geometry(),
                    base.tags()
            ));
        }

        return result;
    }
}

