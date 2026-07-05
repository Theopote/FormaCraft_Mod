package com.formacraft.common.component.model;

import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.archetype.RepeatRule;
import com.formacraft.common.component.archetype.VariationSpec;
import com.formacraft.common.component.variant.Voxel;

import java.util.HashMap;
import java.util.Map;

/**
 * 将 {@link VariationSpec}（Archetype 侧车）桥接为 {@link ComponentPrototype.VariantRules}（SegmentScaler 输入）。
 */
public final class ComponentPrototypeRulesBridge {
    private ComponentPrototypeRulesBridge() {}

    public static ComponentPrototype.VariantRules fromVariationSpec(VariationSpec spec, ComponentDefinition def) {
        if (spec == null || def == null) {
            return null;
        }

        int w = def.size != null && def.size.w > 0 ? def.size.w : 1;
        int h = def.size != null && def.size.h > 0 ? def.size.h : 1;
        int d = def.size != null && def.size.d > 0 ? def.size.d : 1;

        ComponentPrototype.VariantRules rules = new ComponentPrototype.VariantRules();
        ComponentPrototype.VariantRules.Scaling scaling = new ComponentPrototype.VariantRules.Scaling();
        scaling.mode = "SEGMENTED";
        scaling.axes = new HashMap<>();
        scaling.axes.put("X", axisRule(spec, Voxel.Axis.X, w));
        scaling.axes.put("Y", axisRule(spec, Voxel.Axis.Y, h));
        scaling.axes.put("Z", axisRule(spec, Voxel.Axis.Z, d));
        rules.scaling = scaling;
        return rules;
    }

    private static ComponentPrototype.VariantRules.Scaling.AxisRule axisRule(
            VariationSpec spec,
            Voxel.Axis axis,
            int baseSize
    ) {
        RepeatRule repeat = spec.repeatRule;
        if (repeat != null && repeat.enabled && repeatAxisMatches(repeat, axis)) {
            ComponentPrototype.VariantRules.Scaling.AxisRule rule = new ComponentPrototype.VariantRules.Scaling.AxisRule();
            rule.type = "REPEAT";
            rule.segment = "SEG_MID_" + axis.name();
            rule.min = Math.max(1, baseSize * Math.max(1, repeat.minSegments));
            rule.max = Math.max(rule.min, baseSize * Math.max(repeat.minSegments, repeat.maxSegments));
            return rule;
        }

        var scaleRule = switch (axis) {
            case Y -> spec.scaleY;
            case Z -> spec.scaleZ;
            default -> spec.scaleX;
        };

        if (scaleRule != null && !scaleRule.locked) {
            ComponentPrototype.VariantRules.Scaling.AxisRule rule = new ComponentPrototype.VariantRules.Scaling.AxisRule();
            rule.type = "REPEAT";
            rule.segment = "SEG_MID_" + axis.name();
            rule.min = Math.max(1, Math.round(baseSize * scaleRule.min));
            rule.max = Math.max(rule.min, Math.round(baseSize * scaleRule.max));
            return rule;
        }

        ComponentPrototype.VariantRules.Scaling.AxisRule fixed = new ComponentPrototype.VariantRules.Scaling.AxisRule();
        fixed.type = "FIXED";
        return fixed;
    }

    private static boolean repeatAxisMatches(RepeatRule repeat, Voxel.Axis axis) {
        if (repeat == null || repeat.axis == null || axis == null) {
            return false;
        }
        return repeat.axis.name().equals(axis.name());
    }
}
