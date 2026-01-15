package com.formacraft.common.component.archetype;

import java.util.Set;

/**
 * ValidationSpec（验证规格）：防止 AI 犯蠢的约束规则。
 */
public class ValidationSpec {
    /**
     * 禁止内部放置
     */
    public boolean forbidInteriorPlacement = false;

    /**
     * 禁止悬空
     */
    public boolean forbidFloating = true;

    /**
     * 禁止重叠
     */
    public boolean forbidOverlap = true;

    /**
     * 不可共存的构件 ID 列表
     */
    public Set<String> forbiddenWith;

    /**
     * 创建默认的验证规格
     */
    public static ValidationSpec createDefault() {
        ValidationSpec spec = new ValidationSpec();
        spec.forbidInteriorPlacement = false;
        spec.forbidFloating = true;
        spec.forbidOverlap = true;
        spec.forbiddenWith = Set.of();
        return spec;
    }

    /**
     * 创建门的验证规格
     */
    public static ValidationSpec forDoor() {
        ValidationSpec spec = createDefault();
        spec.forbidFloating = true;
        spec.forbidOverlap = true;
        return spec;
    }
}
