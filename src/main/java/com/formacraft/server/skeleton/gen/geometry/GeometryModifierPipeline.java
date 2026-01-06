package com.formacraft.server.skeleton.gen.geometry;

import com.formacraft.common.geometry.GeometryModifier;
import com.formacraft.common.semantic.SemanticPlacementOp;
import com.formacraft.common.style.SemanticStyleProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * GeometryModifierPipeline（几何修饰管道）
 * 
 * 在生成阶段应用几何修饰器
 * 
 * 流程：
 * 1. 基础 SemanticPlacementOp
 * 2. 应用 GeometryModifier（扩展为多个点）
 * 3. 输出扩展后的 SemanticPlacementOp 列表
 */
public final class GeometryModifierPipeline {

    private GeometryModifierPipeline() {}

    /**
     * 应用几何修饰器到语义放置操作列表
     * 
     * @param baseOps 基础语义放置操作列表
     * @param style 风格配置（包含几何修饰器映射）
     * @return 扩展后的语义放置操作列表
     */
    public static List<SemanticPlacementOp> applyModifiers(
            List<SemanticPlacementOp> baseOps,
            SemanticStyleProfile style
    ) {
        if (baseOps == null || baseOps.isEmpty()) {
            return List.of();
        }

        if (style == null) {
            // 没有风格配置，直接返回原始操作
            return baseOps;
        }

        List<SemanticPlacementOp> expanded = new ArrayList<>();

        for (SemanticPlacementOp base : baseOps) {
            if (base == null) continue;

            // 获取该部位的几何修饰器
            GeometryModifier modifier = style.getGeometry(base.part());
            
            if (modifier != null) {
                // 应用修饰器，扩展为多个点
                expanded.addAll(modifier.apply(base));
            } else {
                // 没有修饰器，直接添加原始操作
                expanded.add(base);
            }
        }

        return expanded;
    }
}

