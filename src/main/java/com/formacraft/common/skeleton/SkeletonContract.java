package com.formacraft.common.skeleton;

import java.util.List;

/**
 * Skeleton 语义约束接口
 * 
 * 定义每个 SkeletonType 的语义特性，用于：
 * - LLM 选择骨架类型
 * - Generator 知道如何落地
 * - Tool（轮廓/选区）知道如何约束
 */
public interface SkeletonContract {
    /**
     * 骨架类型
     */
    SkeletonType type();
    
    /**
     * 必需的锚点列表
     * 例如：LINEAR_PATH 需要 start 和 end
     *      RADIAL_SPOKE 需要 center
     */
    List<String> requiredAnchors();
    
    /**
     * 是否需要地形采样
     * true: 需要读取地形高度、坡度等信息
     * false: 可以在任意平面上生成
     */
    boolean requiresTerrainSampling();
    
    /**
     * 是否允许重叠
     * true: 多个模块可以重叠放置
     * false: 需要避免重叠
     */
    boolean allowsOverlap();
    
    /**
     * 是否偏好对称
     * true: 生成器应该尽量保持对称
     * false: 可以不对称
     */
    boolean prefersSymmetry();
    
    /**
     * 是否支持不规则形状
     * true: 可以处理不规则轮廓（如 ENCLOSURE）
     * false: 需要规则形状（如 GRID）
     */
    boolean supportsIrregularShape();
    
    /**
     * 是否是多层结构
     * true: 可以有多层（如 VERTICAL_STACK）
     * false: 单层结构
     */
    boolean isMultiLevel();
    
    /**
     * 是否需要中心点
     * true: 必须有明确的中心（如 RADIAL_RING, RADIAL_SPOKE）
     * false: 不需要中心
     */
    boolean requiresCenter();
    
    /**
     * 是否支持分支
     * true: 可以有分支结构（如 HIERARCHICAL_TREE）
     * false: 线性或单一结构
     */
    boolean supportsBranches();
    
    /**
     * 描述信息（用于 LLM 理解）
     */
    String description();
}

