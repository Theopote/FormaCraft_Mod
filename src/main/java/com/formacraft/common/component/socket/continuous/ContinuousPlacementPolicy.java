package com.formacraft.common.component.socket.continuous;

/**
 * ContinuousPlacementPolicy（连续放置策略）。
 * <p>
 * 这是 H4 的核心控制器，定义了如何沿连续路径放置构件。
 */
public record ContinuousPlacementPolicy(
        /** 单个构件的长度（block） */
        int segmentLength,
        /** 是否居中对齐 */
        boolean alignToCenter,
        /** 是否允许少量重叠 */
        boolean allowOverlap,
        /** 转角处理 */
        CornerHandling cornerMode,
        /** 高度策略 */
        HeightPolicy heightPolicy
) {
    public ContinuousPlacementPolicy {
        if (segmentLength <= 0) {
            segmentLength = 1;
        }
        if (cornerMode == null) {
            cornerMode = CornerHandling.CUT;
        }
        if (heightPolicy == null) {
            heightPolicy = HeightPolicy.FOLLOW_TERRAIN;
        }
    }

    // ========== 预定义策略（四个典型建筑形态） ==========

    /**
     * 城墙（Wall）策略
     */
    public static final ContinuousPlacementPolicy WALL_POLICY = new ContinuousPlacementPolicy(
            3,                      // 3 block 一段
            true,                   // 居中对齐
            false,                  // 不允许重叠
            CornerHandling.PILLAR,  // 转角插柱
            HeightPolicy.STEP_TERRACE // 台阶高度
    );

    /**
     * 栏杆（Railing）策略
     */
    public static final ContinuousPlacementPolicy RAILING_POLICY = new ContinuousPlacementPolicy(
            1,                      // 1 block 一段
            true,                   // 居中对齐
            true,                   // 允许重叠（细节更密）
            CornerHandling.MITER,   // 45° 斜接
            HeightPolicy.FIXED_BASE // 固定高度
    );

    /**
     * 回廊（Colonnade / Corridor）策略
     */
    public static final ContinuousPlacementPolicy COLONNADE_POLICY = new ContinuousPlacementPolicy(
            4,                      // 4 block 一段
            true,                   // 居中对齐
            false,                  // 不允许重叠
            CornerHandling.SMOOTH,  // 平滑过渡
            HeightPolicy.FOLLOW_TERRAIN // 贴地
    );

    /**
     * 长城（Great Wall）策略
     */
    public static final ContinuousPlacementPolicy GREAT_WALL_POLICY = new ContinuousPlacementPolicy(
            5,                      // 5 block 一段
            false,                  // 不强制居中对齐
            false,                  // 不允许重叠
            CornerHandling.PILLAR,  // 转角插柱
            HeightPolicy.ADAPTIVE_FOUNDATION // 自适应底座（高度随山势）
    );
}
