package com.formacraft.common.component;

/**
 * RoofArchetype（屋顶构件原型）
 * <p>
 * 用于屋顶构件的分类和匹配
 * <p>
 * 三大生态位：
 * - RIDGE（脊）
 * - EAVE（檐）
 * - SLOPE_SURFACE（坡面）
 * <p>
 * AI 就是靠这个做"对位思考"的
 */
public enum RoofArchetype {
    /**
     * 屋瓦（坡面铺设）
     * <p>
     * 用途：小青瓦、筒瓦、板瓦等
     * Socket：ROOF_SURFACE
     */
    ROOF_TILE,

    /**
     * 屋脊装饰（脊线）
     * <p>
     * 用途：屋脊兽、脊刹、端头
     * Socket：RIDGE_LINE
     */
    RIDGE_DECORATION,

    /**
     * 檐口装饰（檐口）
     * <p>
     * 用途：滴水瓦、瓦当、檐口装饰
     * Socket：EAVE_LINE
     */
    EAVE_DECORATION,

    /**
     * 檐口结构（檐口）
     * <p>
     * 用途：飞檐、斗拱、檐口结构
     * Socket：EAVE_LINE
     */
    EAVE_STRUCTURAL,

    /**
     * 屋顶装饰（通用）
     * <p>
     * 用途：天窗、脊饰、屋顶装饰
     * Socket：ROOF_SURFACE / RIDGE_LINE
     */
    ROOF_ORNAMENT
}
