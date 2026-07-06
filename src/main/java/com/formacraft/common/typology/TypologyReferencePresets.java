package com.formacraft.common.typology;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Per-reference-landmark param presets applied after registry defaults merge.
 */
public final class TypologyReferencePresets {

    private static final Map<String, Map<String, Object>> PRESETS = new LinkedHashMap<>();

    static {
        PRESETS.put("famen_pagoda", Map.of(
                "footprint", "octagon",
                "levels", 13,
                "height", 47,
                "baseWidth", 10,
                "niche_rhythm", "tier_synced_octagon",
                "detailLevel", "refined"
        ));
        PRESETS.put("giant_wild_goose_pagoda", Map.of(
                "footprint", "square",
                "levels", 7,
                "height", 41,
                "baseWidth", 17,
                "niche_rhythm", "none",
                "detailLevel", "aesthetic",
                "eaveBlock", "minecraft:brick_slab",
                "accentBlock", "minecraft:chiseled_stone_bricks"
        ));
        PRESETS.put("temple_of_heaven", Map.of(
                "baseRadius", 18,
                "tiers", 3,
                "height", 34,
                "hallRadius", 10,
                "detailLevel", "aesthetic",
                "facing", "SOUTH"
        ));
        PRESETS.put("birds_nest_stadium", Map.of(
                "width", 60,
                "depth", 80,
                "height", 28,
                "elliptical", true,
                "meshStructure", true,
                "bowlSteepness", 0.35f,
                "tierStep", 4,
                "facing", "SOUTH"
        ));
    }

    private TypologyReferencePresets() {}

    public static void apply(Map<String, Object> merged) {
        if (merged == null || merged.isEmpty()) {
            return;
        }
        String ref = str(merged.get("reference_landmark"));
        if (ref == null) {
            return;
        }
        Map<String, Object> preset = PRESETS.get(ref.trim().toLowerCase(Locale.ROOT));
        if (preset == null) {
            return;
        }
        for (Map.Entry<String, Object> e : preset.entrySet()) {
            merged.putIfAbsent(e.getKey(), e.getValue());
        }
    }

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }
}
