package com.formacraft.common.skeleton;

/**
 * Topology Skeleton family types.
 * 
 * Skeleton 代表"空间组织方式"，不是几何形状或风格。
 * 每个类型都能映射到一整类建筑，适合城市生成、群体建筑和 AI 规划。
 * 
 * 判断标准：一个 SkeletonType 是否值得存在？
 * → 它是否代表了一种人类直觉中"空间组织方式"？
 * 
 * v1.1: 扩展了空间组织、连接、地形适应三类骨架
 */
public enum SkeletonType {
    // ===== Linear / Path =====
    /** 直线路径（道路、长城） */
    LINEAR_PATH,
    /** 折线路径（山路、城墙） */
    PATH_POLYLINE,
    /** 等高线跟随（山路、长城、依地形路径） */
    CONTOUR_FOLLOW,

    // ===== Radial / Center =====
    /** 闭合环形（土楼、圆形要塞） */
    RADIAL_RING,
    /** 中心辐射（天坛、广场、祭坛、交通核心） */
    RADIAL_SPOKE,

    // ===== Vertical =====
    /** 垂直堆叠（楼层） */
    VERTICAL_STACK,
    /** 向上收缩（塔、尖顶） */
    VERTICAL_TAPER,

    // ===== Area / Enclosure =====
    /** 网格（城市、街区） */
    GRID,
    /** 中庭式（四合院、修道院） */
    COURTYARD,
    /** 轮廓闭环（城墙、院落） */
    PERIMETER_LOOP,
    /** 不规则围合（中式院落、古城城墙、山地要塞） */
    ENCLOSURE,

    // ===== Span / Structure =====
    /** 跨越结构（桥） */
    SPAN_SUSPENSION,

    // ===== Terrain =====
    /** 台地式（梯田、山城） */
    TERRACED,

    // ===== Composite =====
    /** 主从结构（寺庙群、校园、园区） */
    HIERARCHICAL_TREE,
    /** 任意组合（兜底） */
    COMPOUND;

    /**
     * 获取该骨架类型的语义约束
     */
    public SkeletonContract getContract() {
        return SkeletonContractRegistry.getContract(this);
    }
}


