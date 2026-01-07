package com.formacraft.common.cluster.zoning;

import java.util.Set;

/**
 * ISemanticLabelQuery（语义标签查询接口）
 * <p>
 * K3 核心：根据路径进度查询该位置附近的标签集合
 * <p>
 * 例如：{"plaza","gate"} 表示该位置附近有广场和城门标签
 */
public interface ISemanticLabelQuery {
    /**
     * 根据路径进度 t 返回该位置附近的标签集合
     * 
     * @param t 路径进度 [0..1]
     * @return 标签集合（如 {"plaza","gate"}），如果没有标签则返回空集合
     */
    Set<String> queryLabelsNearPathT(float t);
}

