package com.formacraft.common.generation.component.util;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.genome.BuildingGenome;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.mass.derived.FacadeRhythmProfile;
import com.formacraft.common.mass.rhythm.FacadeRhythmPreset;
import com.formacraft.common.mass.rhythm.FacadeRhythmPresetLibrary;

import java.util.BitSet;
import java.util.Locale;
import java.util.Map;

/**
 * 主路径（components[]）立面节奏规划器。
 * <p>
 * 从 rhythm_preset / facade_profile / genome 解析预设，在立面上计算确定性窗位、柱位，
 * 替代 {@code axis % spacing} 的取模开窗，避免窗落在柱位或不对称。
 */
public final class ComponentFacadeRhythmPlanner {

    public static final String PRESET_CLASSICAL_PILASTER_BAY = "CLASSICAL_PILASTER_BAY";

    private ComponentFacadeRhythmPlanner() {}

    public record RhythmPlan(
            int axisMax,
            BitSet windowAxes,
            BitSet pilasterAxes,
            BitSet entranceBayWindowAxes,
            String presetId
    ) {
        public boolean active() {
            return presetId != null && !presetId.isBlank() && windowAxes != null && !windowAxes.isEmpty();
        }

        public boolean isWindowAxis(int axis) {
            return windowAxes != null && axis >= 0 && axis < axisMax && windowAxes.get(axis);
        }

        public boolean isPilasterAxis(int axis) {
            return pilasterAxes != null && axis >= 0 && axis < axisMax && pilasterAxes.get(axis);
        }

        public boolean isEntranceBayAxis(int axis) {
            return entranceBayWindowAxes != null && axis >= 0 && axis < axisMax && entranceBayWindowAxes.get(axis);
        }

        public boolean hasRhythmPilasters() {
            return pilasterAxes != null && !pilasterAxes.isEmpty();
        }

        public static RhythmPlan inactive(int axisMax) {
            return new RhythmPlan(axisMax, null, null, null, null);
        }
    }

    public static RhythmPlan resolve(SemanticComponent semantic, Map<String, Object> params, int axisMax) {
        if (axisMax <= 2) {
            return RhythmPlan.inactive(axisMax);
        }
        String presetId = resolvePresetId(semantic, params);
        if (presetId == null || presetId.isBlank()) {
            return RhythmPlan.inactive(axisMax);
        }
        FacadeRhythmPresetLibrary.initialize();
        FacadeRhythmPreset preset = FacadeRhythmPresetLibrary.getPreset(presetId);
        int spacing = preset != null && preset.profile() != null ? preset.profile().spacing : 3;
        boolean bilateral = isBilateral(semantic, preset);
        BitSet windows = computeWindowAxes(presetId, axisMax, spacing, bilateral);
        if (windows.isEmpty()) {
            return RhythmPlan.inactive(axisMax);
        }
        BitSet pilasters = computePilasterAxes(presetId, axisMax, windows);
        BitSet entranceBay = computeEntranceBayWindowAxes(presetId, axisMax);
        return new RhythmPlan(axisMax, windows, pilasters, entranceBay, presetId);
    }

    static BitSet computeWindowAxes(String presetId, int axisMax, int spacing, boolean bilateral) {
        if (presetId == null || axisMax <= 2) {
            return new BitSet();
        }
        String id = presetId.trim().toUpperCase(Locale.ROOT);
        return switch (id) {
            case PRESET_CLASSICAL_PILASTER_BAY -> computeClassicalPilasterBayAxes(axisMax);
            case "PALACE_HIERARCHICAL", "TEMPLE_AXIAL" ->
                    bilateral ? computeRegularBilateralAxes(axisMax, Math.max(3, spacing)) : computeRegularAxes(axisMax, Math.max(3, spacing));
            case "RESIDENTIAL_GROUPED" -> computeRegularBilateralAxes(axisMax, Math.max(4, spacing));
            case "INDUSTRIAL_GRID" -> computeEdgeAlignedAxes(axisMax, Math.max(2, spacing));
            case "RESIDENTIAL_REGULAR" -> computeRegularAxes(axisMax, Math.max(2, spacing));
            default -> computeRegularBilateralAxes(axisMax, Math.max(2, spacing));
        };
    }

    /**
     * 柱-窗×3-柱 开间（unit=5），以立面中心对称展开。
     * 角部（axis 0 / axisMax-1）保留实墙。
     */
    static BitSet computeClassicalPilasterBayAxes(int axisMax) {
        BitSet windows = new BitSet();
        if (axisMax <= 4) {
            return windows;
        }
        for (int bayCenter : listClassicalBayCenters(axisMax)) {
            for (int d = -1; d <= 1; d++) {
                int axis = bayCenter + d;
                if (axis > 0 && axis < axisMax - 1) {
                    windows.set(axis);
                }
            }
        }
        return windows;
    }

    /** P-W-W-W-P 单元中的 P（柱位），含角柱。 */
    static BitSet computeClassicalPilasterAxes(int axisMax) {
        BitSet pilasters = new BitSet();
        if (axisMax <= 2) {
            return pilasters;
        }
        pilasters.set(0);
        pilasters.set(axisMax - 1);
        if (axisMax <= 4) {
            return pilasters;
        }
        for (int bayCenter : listClassicalBayCenters(axisMax)) {
            markIfInterior(pilasters, bayCenter - 2, axisMax);
            markIfInterior(pilasters, bayCenter + 2, axisMax);
        }
        return pilasters;
    }

    /** 主入口开间（center bay）窗位 — 正立面由 ENTRANCE 占用，不在此开窗。 */
    static BitSet computeClassicalEntranceBayWindowAxes(int axisMax) {
        BitSet axes = new BitSet();
        if (axisMax <= 4) {
            return axes;
        }
        int center = axisMax / 2;
        for (int d = -1; d <= 1; d++) {
            markIfInterior(axes, center + d, axisMax);
        }
        return axes;
    }

    static BitSet computePilasterAxes(String presetId, int axisMax, BitSet windowAxes) {
        if (presetId == null || axisMax <= 2) {
            return new BitSet();
        }
        String id = presetId.trim().toUpperCase(Locale.ROOT);
        if (PRESET_CLASSICAL_PILASTER_BAY.equals(id)) {
            return computeClassicalPilasterAxes(axisMax);
        }
        return new BitSet();
    }

    static BitSet computeEntranceBayWindowAxes(String presetId, int axisMax) {
        if (presetId == null || axisMax <= 4) {
            return new BitSet();
        }
        String id = presetId.trim().toUpperCase(Locale.ROOT);
        if (PRESET_CLASSICAL_PILASTER_BAY.equals(id)) {
            return computeClassicalEntranceBayWindowAxes(axisMax);
        }
        return new BitSet();
    }

    private static java.util.List<Integer> listClassicalBayCenters(int axisMax) {
        java.util.List<Integer> bayCenters = new java.util.ArrayList<>();
        int center = axisMax / 2;
        int unit = 5;
        bayCenters.add(center);
        for (int step = unit; ; step += unit) {
            boolean added = false;
            int left = center - step;
            int right = center + step;
            if (left > 0 && left < axisMax - 1) {
                bayCenters.add(left);
                added = true;
            }
            if (right > 0 && right < axisMax - 1) {
                bayCenters.add(right);
                added = true;
            }
            if (!added) {
                break;
            }
        }
        return bayCenters;
    }

    private static void markIfInterior(BitSet target, int axis, int axisMax) {
        if (axis > 0 && axis < axisMax - 1) {
            target.set(axis);
        }
    }

    static BitSet computeRegularBilateralAxes(int axisMax, int spacing) {
        BitSet axes = new BitSet();
        if (axisMax <= 2 || spacing <= 0) {
            return axes;
        }
        int center = axisMax / 2;
        if (center > 0 && center < axisMax - 1) {
            axes.set(center);
        }
        for (int d = spacing; center - d > 0 || center + d < axisMax - 1; d += spacing) {
            if (center - d > 0) {
                axes.set(center - d);
            }
            if (center + d < axisMax - 1) {
                axes.set(center + d);
            }
        }
        return axes;
    }

    static BitSet computeRegularAxes(int axisMax, int spacing) {
        BitSet axes = new BitSet();
        if (axisMax <= 2 || spacing <= 0) {
            return axes;
        }
        for (int axis = 1; axis < axisMax - 1; axis += spacing) {
            axes.set(axis);
        }
        return axes;
    }

    static BitSet computeEdgeAlignedAxes(int axisMax, int spacing) {
        BitSet axes = new BitSet();
        if (axisMax <= 2 || spacing <= 0) {
            return axes;
        }
        for (int axis = 1; axis < axisMax - 1; axis += spacing) {
            axes.set(axis);
        }
        return axes;
    }

    static String resolvePresetId(SemanticComponent semantic, Map<String, Object> params) {
        String explicit = getParamString(params, "rhythm_preset", "rhythmPreset", "facade_rhythm_preset");
        if (explicit != null && !explicit.isBlank()) {
            return normalizePresetAlias(explicit);
        }
        String rhythm = getParamString(params, "rhythm");
        if ("vertical_bay".equalsIgnoreCase(rhythm) || "pilaster_bay".equalsIgnoreCase(rhythm)) {
            return PRESET_CLASSICAL_PILASTER_BAY;
        }
        String facadeProfile = getParamString(params, "facade_profile", "facadeProfile", "facade");
        if (facadeProfile != null) {
            String fp = facadeProfile.toLowerCase(Locale.ROOT);
            if (fp.contains("pilaster") || fp.contains("colonnade") || fp.contains("classical")) {
                return PRESET_CLASSICAL_PILASTER_BAY;
            }
            if (fp.contains("mullion") || fp.contains("module_grid") || fp.contains("curtain")) {
                return "INDUSTRIAL_GRID";
            }
        }
        Component c = semantic != null ? semantic.source() : null;
        if (c != null && c.features() != null) {
            for (String feature : c.features()) {
                if (feature == null) {
                    continue;
                }
                String lower = feature.toLowerCase(Locale.ROOT);
                if (lower.contains("facade_rhythm") || lower.contains("pilaster")) {
                    return PRESET_CLASSICAL_PILASTER_BAY;
                }
            }
        }
        BuildingGenome genome = semantic != null ? semantic.genome() : null;
        if (genome != null) {
            if (genome.symmetry != null) {
                String sym = genome.symmetry.type != null ? genome.symmetry.type.toLowerCase(Locale.ROOT) : "";
                if (sym.contains("bilateral") || Boolean.TRUE.equals(genome.symmetry.mirror)) {
                    String formRhythm = genome.form != null ? genome.form.rhythm : null;
                    if ("segmented".equalsIgnoreCase(formRhythm)) {
                        return "RESIDENTIAL_GROUPED";
                    }
                    if ("irregular".equalsIgnoreCase(formRhythm)) {
                        return null;
                    }
                }
            }
            if (genome.form != null && "vertical".equalsIgnoreCase(genome.form.repetition)) {
                return PRESET_CLASSICAL_PILASTER_BAY;
            }
        }
        return null;
    }

    private static boolean isBilateral(SemanticComponent semantic, FacadeRhythmPreset preset) {
        if (preset != null && preset.profile() != null) {
            FacadeRhythmProfile.SymmetryMode mode = preset.profile().symmetry;
            if (mode == FacadeRhythmProfile.SymmetryMode.BILATERAL) {
                return true;
            }
        }
        BuildingGenome genome = semantic != null ? semantic.genome() : null;
        if (genome == null || genome.symmetry == null) {
            return true;
        }
        String sym = genome.symmetry.type != null ? genome.symmetry.type.toLowerCase(Locale.ROOT) : "";
        return sym.contains("bilateral") || sym.contains("mirror") || Boolean.TRUE.equals(genome.symmetry.mirror);
    }

    private static String normalizePresetAlias(String raw) {
        String v = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if ("VERTICAL_BAY".equals(v) || "PILASTER_BAY".equals(v) || "CLASSICAL_BAY".equals(v)) {
            return PRESET_CLASSICAL_PILASTER_BAY;
        }
        return v;
    }

    private static String getParamString(Map<String, Object> params, String... keys) {
        if (params == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key == null) {
                continue;
            }
            Object v = params.get(key);
            if (v == null) {
                continue;
            }
            String s = String.valueOf(v).trim();
            if (!s.isEmpty()) {
                return s;
            }
        }
        return null;
    }
}
