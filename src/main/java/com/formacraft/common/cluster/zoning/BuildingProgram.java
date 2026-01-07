package com.formacraft.common.cluster.zoning;

/**
 * BuildingProgram（建筑功能/业态）
 * 
 * 用于 Prompt 和 Generator 选预设
 * 
 * K3 核心：定义建筑的功能类型，让 AI 理解规划意图
 */
public enum BuildingProgram {
    /** 住宅 */
    RESIDENTIAL,
    
    /** 商业 */
    COMMERCIAL,
    
    /** 混合用途 */
    MIXED_USE,
    
    /** 工业 */
    INDUSTRIAL,
    
    /** 市政：大厅、办公、学校 */
    CIVIC,
    
    /** 宗教：寺庙、教堂 */
    RELIGIOUS,
    
    /** 防御：塔楼、城墙节点 */
    DEFENSIVE,
    
    /** 地标：钟楼、牌坊、巨像 */
    LANDMARK,
    
    /** 绿地/花园（可以生成景观组件而非房子） */
    PARK,
    
    /** 广场（偏铺装+小物件） */
    PLAZA,
    
    /** 港口/码头 */
    PORT,
    
    /** 农田/仓房 */
    FARM
}

