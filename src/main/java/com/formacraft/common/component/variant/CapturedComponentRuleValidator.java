package com.formacraft.common.component.variant;

import com.formacraft.common.component.ComponentDefinition;

/** Shared capture/save validation; malformed permissions never silently become a global scale. */
public final class CapturedComponentRuleValidator {
    private CapturedComponentRuleValidator() {}
    public static String validate(ComponentDefinition def) {
        if (def == null || def.size == null) return "构件缺少尺寸";
        if (def.variantRules == null) return null;
        var rules = def.variantRules.scaling;
        if (rules == null || rules.axes == null) return null;
        int[] sizes = {def.size.w, def.size.h, def.size.d};
        for (Voxel.Axis axis : Voxel.Axis.values()) {
            var rule = rules.axes.get(axis.name());
            if (rule == null || "FIXED".equalsIgnoreCase(rule.type)) continue;
            if (!"REPEAT".equalsIgnoreCase(rule.type) && !"TRIM".equalsIgnoreCase(rule.type))
                return axis + " 方向变化方式无效";
            int start = rule.fixedStart == null ? 1 : rule.fixedStart;
            int end = rule.fixedEnd == null ? 1 : rule.fixedEnd;
            if (start < 0 || end < 0 || start + end >= sizes[axis.ordinal()])
                return axis + " 方向没有可变中段；请减少固定边厚度或锁定该方向";
            if (rule.min != null && rule.max != null && rule.min > rule.max)
                return axis + " 方向尺寸范围无效";
        }
        return null;
    }
}
