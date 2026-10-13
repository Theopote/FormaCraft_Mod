package com.formacraft.common.component.variant;

import com.formacraft.common.component.ComponentDefinition;

import java.util.ArrayList;
import java.util.List;

/** Shared capture/save validation; malformed permissions never silently become a global scale. */
public final class CapturedComponentRuleValidator {
    private CapturedComponentRuleValidator() {}

    public static String validate(ComponentDefinition def) {
        if (def == null || def.variantRules == null) return null;
        if (def.size == null) return "构件缺少尺寸";
        var rules = def.variantRules.scaling;
        if (rules == null || rules.axes == null) return null;
        int[] sizes = {def.size.w, def.size.h, def.size.d};
        List<Voxel> voxels = voxelsFrom(def);
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
            if (!voxels.isEmpty()) {
                int min = voxels.stream().mapToInt(v -> SegmentScaler.coord(v, axis)).min().orElse(0);
                int max = voxels.stream().mapToInt(v -> SegmentScaler.coord(v, axis)).max().orElse(min);
                int original = max - min + 1;
                // Prefer author fixedStart/End; otherwise keep validator defaults matching mapping().
                int segStart = start;
                int segEnd = end;
                if (rule.fixedStart == null && rule.fixedEnd == null) {
                    String tag = rule.segment != null ? rule.segment : "SEG_MID_" + axis;
                    var tagged = voxels.stream().filter(v -> v.hasSegmentTag(tag)).toList();
                    if (!tagged.isEmpty()) {
                        segStart = tagged.stream().mapToInt(v -> SegmentScaler.coord(v, axis)).min().orElse(min) - min;
                        segEnd = max - tagged.stream().mapToInt(v -> SegmentScaler.coord(v, axis)).max().orElse(max);
                    }
                }
                String paired = SegmentScaler.middlePairedBlockMessage(voxels, axis, min, original, segStart, segEnd);
                if (paired != null) return paired;
            }
        }
        return null;
    }

    private static List<Voxel> voxelsFrom(ComponentDefinition def) {
        var out = new ArrayList<Voxel>();
        if (def.blocks == null) return out;
        for (var be : def.blocks) {
            if (be == null || be.block == null) continue;
            out.add(new Voxel(be.dx, be.dy, be.dz, be.block));
        }
        return out;
    }
}
