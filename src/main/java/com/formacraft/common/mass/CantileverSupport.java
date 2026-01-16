package com.formacraft.common.mass;

/**
 * CantileverSupport（悬挑支撑）
 * <p>
 * 定义悬挑体量的支撑关系
 * <p>
 * ⚠️ 关键：
 * - 悬挑不是"漂浮"
 * - 悬挑是"在下方没有体量，但上方允许存在"的规则
 * - 这是 Minecraft 友好的"伪结构判断"
 * - 你不是在算力学，只是在限制生成
 */
public class CantileverSupport {
    /** 支撑体量 ID */
    public final String supportMassId;

    /** 最大挑出距离（block，离散的方块数） */
    public final int maxOverhang;

    public CantileverSupport(String supportMassId, int maxOverhang) {
        this.supportMassId = supportMassId;
        this.maxOverhang = maxOverhang;
    }

    /**
     * 检查悬挑距离是否在允许范围内
     *
     * @param distanceFromSupport 距离支撑体量的距离（block）
     * @return 是否允许
     */
    public boolean allowsOverhang(int distanceFromSupport) {
        return distanceFromSupport <= maxOverhang;
    }
}
