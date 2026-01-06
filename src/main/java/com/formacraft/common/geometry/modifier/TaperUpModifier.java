package com.formacraft.common.geometry.modifier;

import com.formacraft.common.geometry.GeometryModifier;
import com.formacraft.common.semantic.SemanticPlacementOp;

import java.util.ArrayList;
import java.util.List;

/**
 * TaperUpModifier（向上收分修饰器）
 * 
 * 将单点扩展为垂直堆叠的多点
 * 
 * 应用场景：
 * - 塔楼
 * - 尖顶
 * - 哥特垂直感
 */
public class TaperUpModifier implements GeometryModifier {

    private final int height;

    public TaperUpModifier(int height) {
        this.height = Math.max(1, height);
    }

    @Override
    public List<SemanticPlacementOp> apply(SemanticPlacementOp base) {
        List<SemanticPlacementOp> ops = new ArrayList<>();
        
        for (int y = 0; y < height; y++) {
            ops.add(new SemanticPlacementOp(
                    base.pos().up(y),
                    base.facing(),
                    base.part(),
                    base.role(),
                    base.geometry(),
                    base.tags()
            ));
        }
        
        return ops;
    }
}

