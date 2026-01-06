package com.formacraft.common.terrain;

/**
 * Terrain strategy for generation.
 * 
 * 地形策略不是开关，而是一个枚举 + 权重
 * 
 * v1: keep small and stable.
 */
public enum TerrainStrategy {
    /**
     * 保护地形（PRESERVE）
     * 
     * 不削山、不填谷
     * 建筑抬高 / 架空 / 台阶连接
     * 
     * 适合：
     * - 日式、精灵风
     * - 山地寺庙
     * - 景观建筑
     */
    PRESERVE,

    /**
     * 自适应（ADAPTIVE）- 默认推荐
     * 
     * 单体建筑各自处理底座
     * 建筑群不做整体平整
     * 建筑之间用：台阶、缓坡、小桥
     * 
     * 适合：
     * - 村落
     * - 山地城镇
     * - 多建筑办公群
     * - 长城 / 道路 / 聚落
     * 
     * 这是"最优默认解"
     */
    ADAPTIVE,

    /**
     * 梯田/台地（TERRACE）
     * 
     * 把地形离散为几个高度平台
     * 每个平台放一组建筑
     * 平台之间用台阶/坡道
     * 
     * 适合：
     * - 山城
     * - 中国古城
     * - 中世纪山地要塞
     */
    TERRACE,

    /**
     * 强制平整（FLATTEN）
     * 
     * 大范围填平
     * 高成本但规则
     * 
     * 只在用户明确说：
     * - "完全平整地形"
     * - "工业园区"
     * - "现代城市核心区"
     */
    FLATTEN
}

