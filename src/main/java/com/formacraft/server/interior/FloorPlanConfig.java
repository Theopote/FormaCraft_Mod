package com.formacraft.server.interior;

import com.formacraft.server.generation.structure.util.StructureSpecParsers;

import java.util.Locale;
import java.util.Map;

/**
 * FloorPlanConfig (v1):
 * LLM-facing config for functional interiors.
 *
 * Source: BuildingSpec.extra.floor_plan_logic (or floorPlanLogic).
 *
 * This is intentionally "IR-like": semantic knobs, not block ids.
 */
public final class FloorPlanConfig {
    public final String corePosition;      // CENTER (v1)
    public final int corridorWidth;        // 1..4
    public final String partitionStyle;    // OPEN_PLAN / DENSE_GRID
    public final int roomMinSize;          // 3..12
    public final int coreW;               // 4..10
    public final int coreD;               // 4..10

    private FloorPlanConfig(String corePosition, int corridorWidth, String partitionStyle, int roomMinSize, int coreW, int coreD) {
        this.corePosition = corePosition;
        this.corridorWidth = corridorWidth;
        this.partitionStyle = partitionStyle;
        this.roomMinSize = roomMinSize;
        this.coreW = coreW;
        this.coreD = coreD;
    }

    public static FloorPlanConfig defaults() {
        return new FloorPlanConfig("CENTER", 2, "OPEN_PLAN", 6, 6, 6);
    }

    @SuppressWarnings("unchecked")
    public static FloorPlanConfig fromExtra(Object v) {
        if (!(v instanceof Map<?, ?> mm)) return null;
        Map<String, Object> cfg;
        try { cfg = (Map<String, Object>) mm; } catch (Exception e) { return null; }
        if (cfg == null || cfg.isEmpty()) return null;

        FloorPlanConfig def = defaults();
        String corePos = asUpper(cfg.get("core_position"), def.corePosition);
        int corridor = clampInt(cfg.get("corridor_width"), def.corridorWidth, 1, 4);
        String style = asUpper(cfg.get("partition_style"), def.partitionStyle);
        int minRoom = clampInt(cfg.get("room_min_size"), def.roomMinSize, 3, 12);
        int coreW = clampInt(firstNonNull(cfg, "core_w", "coreWidth"), def.coreW, 4, 10);
        int coreD = clampInt(firstNonNull(cfg, "core_d", "coreDepth"), def.coreD, 4, 10);

        return new FloorPlanConfig(corePos, corridor, style, minRoom, coreW, coreD);
    }

    private static Object firstNonNull(Map<String, Object> m, String k1, String k2) {
        if (m == null) return null;
        Object v = m.get(k1);
        return v != null ? v : m.get(k2);
    }

    private static String asUpper(Object v, String def) {
        if (v == null) return def;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? def : s.toUpperCase(Locale.ROOT);
    }

    private static int clampInt(Object v, int def, int min, int max) {
        int n = StructureSpecParsers.intValue(v, def);
        return clamp(n, min, max);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}


