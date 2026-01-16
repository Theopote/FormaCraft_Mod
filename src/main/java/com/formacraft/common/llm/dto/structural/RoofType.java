package com.formacraft.common.llm.dto.structural;

/**
 * RoofType（屋顶类型）
 * <p>
 * v1 只做"占位语义"，不处理几何细节
 * <p>
 * ⚠️ v1 不要在这里处理：
 * - 坡度
 * - 檐口
 * - 脊线
 * <p>
 * 这些是 v2 的事
 */
public enum RoofType {
    /**
     * 平屋顶
     */
    FLAT,

    /**
     * 通用屋顶（占位，未来细化）
     */
    GENERIC
}
