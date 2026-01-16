package com.formacraft.common.llm.dto.structural;

/**
 * GroundingMode（地基贴合方式）
 * <p>
 * 这是 Terrain 感知 → 建筑 的第一个连接点
 */
public enum GroundingMode {
    /** 强制水平 */
    FLAT,
    /** 跟随地形 */
    FOLLOW_TERRAIN,
    /** 台阶化（未来） */
    STEP_TERRACE
}
