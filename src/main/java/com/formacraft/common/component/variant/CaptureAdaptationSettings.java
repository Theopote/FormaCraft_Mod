package com.formacraft.common.component.variant;

import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.model.ComponentPrototype;

import java.util.LinkedHashMap;

/** Author settings saved with a capture; default is an immutable original. */
public final class CaptureAdaptationSettings {
    public enum Mode { FIXED, REPEAT, TRIM }
    public Mode[] modes = {Mode.FIXED, Mode.FIXED, Mode.FIXED};
    public int[] fixedEdges = {1, 1, 1};
    /** Optional high-coordinate ends; null preserves the legacy symmetric setting. */
    public Integer[] fixedEndEdges = {null, null, null};
    public boolean allowMaterialSwap = false;

    public CaptureAdaptationSettings copy() {
        var out = new CaptureAdaptationSettings();
        out.modes = modes.clone();
        out.fixedEdges = fixedEdges.clone();
        out.fixedEndEdges = fixedEndEdges == null ? new Integer[3] : fixedEndEdges.clone();
        out.allowMaterialSwap = allowMaterialSwap;
        return out;
    }

    public int fixedEnd(int axis) {
        return fixedEndEdges == null || fixedEndEdges[axis] == null ? fixedEdges[axis] : fixedEndEdges[axis];
    }

    /** Freeze both ends on first separate edit, so changing one never changes the other. */
    public void setFixedEdge(int axis, boolean end, int layers) {
        if (layers < 1 || layers > SegmentScaler.MAX_AXIS_SIZE)
            throw new IllegalArgumentException("Fixed edge must be between 1 and " + SegmentScaler.MAX_AXIS_SIZE);
        int previousEnd = fixedEnd(axis);
        if (fixedEndEdges == null) fixedEndEdges = new Integer[3];
        fixedEndEdges[axis] = end ? layers : previousEnd;
        if (!end) fixedEdges[axis] = layers;
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
            rule.fixedEnd = fixedEnd(i);
            rule.segment = "SEG_MID_" + axis;
            rule.min = CaptureBandLayout.of(this, i, dimensions[i]).minimumSize();
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
