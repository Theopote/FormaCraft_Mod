package com.formacraft.common.cluster.zoning;

import com.formacraft.common.cluster.StreetSide;

import java.util.EnumSet;
import java.util.Set;

/**
 * ZoneRule（分区规则）
 * 
 * 按路径进度 / 侧 / lane / 标签触发
 * 
 * K3 核心：定义何时何地应用哪种建筑功能
 */
public record ZoneRule(
        /** 规则 ID */
        String id,
        
        /** 路径进度起始 [0..1] */
        float startT,
        
        /** 路径进度结束 [0..1] */
        float endT,
        
        /** 适用的侧（LEFT/RIGHT，可空=两侧） */
        Set<StreetSide> sides,
        
        /** 适用的 lane（null=所有 lane；否则特定 lane） */
        Integer laneIndex,
        
        /** 需要的 SemanticLabel（可为 null） */
        String requiredLabel,
        
        /** 建筑功能 */
        BuildingProgram program,
        
        /** 权重（同时命中多条规则时的优先级） */
        float weight
) {
    /**
     * 检查规则是否匹配
     * 
     * @param t 路径进度 [0..1]
     * @param side 街道侧
     * @param lane lane 索引
     * @param labelsAtT 该位置的标签集合
     * @return 是否匹配
     */
    public boolean match(float t, StreetSide side, int lane, Set<String> labelsAtT) {
        // 检查路径进度
        if (t < startT || t > endT) return false;
        
        // 检查侧
        if (sides != null && !sides.isEmpty() && !sides.contains(side)) return false;
        
        // 检查 lane
        if (laneIndex != null && laneIndex != lane) return false;
        
        // 检查标签
        if (requiredLabel != null && (labelsAtT == null || !labelsAtT.contains(requiredLabel))) {
            return false;
        }
        
        return true;
    }

    /**
     * 创建两侧都适用的侧集合
     */
    public static Set<StreetSide> bothSides() {
        return EnumSet.of(StreetSide.LEFT, StreetSide.RIGHT);
    }
}

