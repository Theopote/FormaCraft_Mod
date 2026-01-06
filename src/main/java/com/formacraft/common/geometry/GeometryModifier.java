package com.formacraft.common.geometry;

import com.formacraft.common.semantic.SemanticPlacementOp;

import java.util.List;

/**
 * GeometryModifier（几何修饰器）
 * 
 * 将一个语义放置点扩展为多个几何放置点
 * 
 * 注意：Modifier 不直接放方块，只"改坐标集合"
 */
public interface GeometryModifier {

    /**
     * 将一个语义放置点扩展为多个几何放置点
     * 
     * @param base 基础语义放置操作
     * @return 扩展后的语义放置操作列表
     */
    List<SemanticPlacementOp> apply(SemanticPlacementOp base);
}

