package com.formacraft.common.component.variant;

import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.model.ComponentPrototype;

import java.util.LinkedHashMap;

/** Author settings saved with a capture; default is an immutable original. */
public final class CaptureAdaptationSettings {
    public enum Mode { FIXED, REPEAT, TRIM }
    public Mode[] modes = {Mode.FIXED, Mode.FIXED, Mode.FIXED};
    public int[] fixedEdges = {1, 1, 1};
    public boolean allowMaterialSwap = false;

    public CaptureAdaptationSettings copy() {
        var out = new CaptureAdaptationSettings();
        out.modes = modes.clone();
        out.fixedEdges = fixedEdges.clone();
        out.allowMaterialSwap = allowMaterialSwap;
        return out;
    }

    public ComponentPrototype.VariantRules toRules(ComponentDefinition def) {
        var rules = new ComponentPrototype.VariantRules();
        rules.scaling = new ComponentPrototype.VariantRules.Scaling();
        rules.scaling.mode = "SEGMENTED";
        rules.scaling.axes = new LinkedHashMap<>();
        int[] dimensions = {def.size.w, def.size.h, def.size.d};
        for (Voxel.Axis axis : Voxel.Axis.values()) {
            int i = axis.ordinal();
            var rule = new ComponentPrototype.VariantRules.Scaling.AxisRule();
            rule.type = modes[i].name();
            rule.fixedStart = fixedEdges[i];
            rule.fixedEnd = fixedEdges[i];
            rule.segment = "SEG_MID_" + axis;
            rule.min = modes[i] == Mode.FIXED ? dimensions[i] : fixedEdges[i] * 2 + 1;
            rule.max = modes[i] == Mode.REPEAT ? Math.min(SegmentScaler.MAX_AXIS_SIZE,
                    Math.max(dimensions[i], dimensions[i] * 4)) : dimensions[i];
            rules.scaling.axes.put(axis.name(), rule);
        }
        if (allowMaterialSwap) {
            rules.material = new ComponentPrototype.VariantRules.Material();
            rules.material.semantic_map = new LinkedHashMap<>();
            for (String part : new String[]{"WALL", "GENERIC", "FOUNDATION", "PILLAR", "BEAM",
                    "WINDOW", "FLOOR", "STAIR_STEP", "STAIRS", "RAILING", "ROOF", "TRIM"}) {
                String key = switch (part) {
                    case "FOUNDATION" -> "foundation";
                    case "PILLAR", "BEAM" -> "pillar";
                    case "WINDOW" -> "window";
                    case "FLOOR" -> "floor";
                    case "STAIR_STEP", "STAIRS", "ROOF" -> "roof";
                    case "RAILING", "TRIM" -> "trim";
                    default -> "wall";
                };
                rules.material.semantic_map.put(part, key);
            }
        }
        return rules;
    }
}
