package com.formacraft.server.foundation;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.server.terrain.TerrainFit;

import java.util.Locale;
import java.util.Map;

/**
 * FoundationPlanner (v1):
 * - chooses a FoundationType from footprint terrain range
 * - derives recommended padDepth/clearHeight knobs (still executed by TerrainFit + budget degradation)
 *
 * Important:
 * - This planner does NOT place blocks by itself; it only provides decisions/knobs.
 * - Execution stays in existing TerrainFit/adaptivePad + budget controls.
 */
public final class FoundationPlanner {
    private FoundationPlanner() {}

    public record Decision(FoundationType type, int padDepth, int clearHeight, boolean stilt) {}

    public static Decision decide(BuildingSpec spec,
                                  TerrainFit.FootprintAnalysis analysis,
                                  int basePadDepth,
                                  int baseClearHeight) {
        int range = analysis != null ? analysis.range() : 0;
        int height = spec != null ? Math.max(4, spec.getHeight()) : 12;
        Map<String, Object> extra = spec != null ? spec.getExtra() : null;
        FoundationType type = chooseType(range, height, extra);
        return knobsFor(type, range, height, basePadDepth, baseClearHeight);
    }

    public static FoundationType chooseType(int range, int buildingHeight, Map<String, Object> extra) {
        // explicit override (AI/user/system): foundationType / footingType
        FoundationType ov = parseOverride(extra != null ? extra.get("foundationType") : null);
        if (ov == null) ov = parseOverride(extra != null ? extra.get("footingType") : null);
        if (ov != null) return ov;

        int r = Math.max(0, range);
        int h = Math.max(4, buildingHeight);

        if (r <= 1) return FoundationType.FLAT_PAD;
        if (r <= 6) return FoundationType.STEPPED;

        // If terrain variance is very large relative to building height, prefer stilt.
        int stiltThreshold = Math.max(11, (int) Math.round(h * 0.40));
        if (r >= stiltThreshold) return FoundationType.STILT;

        // Otherwise, treat as hillside-ish embedding (cut/clear more, fill less).
        return FoundationType.EMBEDDED;
    }

    public static Decision knobsFor(FoundationType type,
                                    int range,
                                    int buildingHeight,
                                    int basePadDepth,
                                    int baseClearHeight) {
        int pad = clamp(basePadDepth, 0, 6);
        int clear = clamp(baseClearHeight, 0, 16);
        int r = Math.max(0, range);
        int h = Math.max(4, buildingHeight);

        return switch (type) {
            case STILT -> new Decision(FoundationType.STILT, 0, clamp(Math.max(2, clear / 2), 0, 16), true);
            case EMBEDDED -> {
                // Prefer carving/clearing more than filling on slopes.
                int clear2 = clamp(Math.max(clear, Math.min(16, (h / 3) + 3)), 0, 16);
                yield new Decision(FoundationType.EMBEDDED, 0, clear2, false);
            }
            case STEPPED -> {
                // Medium variance: slightly stronger pad and standard clear.
                int pad2 = clamp(Math.max(2, pad), 0, 6);
                if (r >= 7) pad2 = clamp(Math.max(pad2, 4), 0, 6);
                yield new Decision(FoundationType.STEPPED, pad2, clear, false);
            }
            case FLAT_PAD -> {
                // Small variance: keep edits minimal.
                int pad2 = clamp(Math.max(1, Math.min(2, pad <= 0 ? 1 : pad)), 0, 6);
                int clear2 = clamp(Math.min(clear, 6), 0, 16);
                yield new Decision(FoundationType.FLAT_PAD, pad2, clear2, false);
            }
        };
    }

    private static FoundationType parseOverride(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        return switch (s) {
            case "flat_pad", "flat", "pad" -> FoundationType.FLAT_PAD;
            case "stepped", "step", "terrace" -> FoundationType.STEPPED;
            case "stilt", "stilts", "pier" -> FoundationType.STILT;
            case "embedded", "embed" -> FoundationType.EMBEDDED;
            default -> null;
        };
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}


