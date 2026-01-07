package com.formacraft.common.cluster.zoning;

import com.formacraft.common.cluster.StreetSide;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * PathZoningPlanner（路径分区规划器）
 * 
 * 根据 ZoningProfile 为每个 BuildingSlot 计算 Program
 * 
 * K3 核心：将分区规则应用到具体的建筑槽位
 */
public final class PathZoningPlanner {

    private final ZoningProfile profile;

    public PathZoningPlanner(ZoningProfile profile) {
        this.profile = profile != null ? profile : new ZoningProfile("DEFAULT");
    }

    /**
     * 解析建筑功能
     * 
     * @param t 路径进度 [0..1]
     * @param side 街道侧
     * @param lane lane 索引
     * @param labelsAtT 该位置的标签集合
     * @return 建筑功能
     */
    public BuildingProgram resolve(float t, StreetSide side, int lane, Set<String> labelsAtT) {
        ZoneRule best = null;
        float bestWeight = -1f;

        List<ZoneRule> rules = profile.rules;
        for (ZoneRule r : rules) {
            if (!r.match(t, side, lane, labelsAtT != null ? labelsAtT : emptyLabels())) {
                continue;
            }
            if (r.weight() > bestWeight) {
                bestWeight = r.weight();
                best = r;
            }
        }

        // 默认兜底：住宅（最稳）
        if (best == null) {
            return BuildingProgram.RESIDENTIAL;
        }
        
        return best.program();
    }

    /**
     * 创建空标签集合
     */
    public static Set<String> emptyLabels() {
        return new HashSet<>();
    }
}

