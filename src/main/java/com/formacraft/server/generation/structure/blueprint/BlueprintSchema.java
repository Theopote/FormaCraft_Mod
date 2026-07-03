package com.formacraft.server.generation.structure.blueprint;

import com.formacraft.common.logging.FcaLog;
import java.util.Locale;
import java.util.Map;

/**
 * BlueprintSchema (v1):
 * Minimal, forward-compatible validation for semantic blueprints.
 *
 * Goals:
 * - Provide stable keys (blueprint_type + blueprint_version) for compilers.
 * - Tolerate common aliases while avoiding silent failures.
 * - Keep validation lightweight (no hard dependency on a full JSON schema engine).
 */
public final class BlueprintSchema {

    private static final FcaLog LOG = FcaLog.of("BlueprintSchema");
    private BlueprintSchema() {}

    public static final int SUPPORTED_VERSION = 1;

    public record Validation(boolean ok, String type, int version, String error) {}

    public static Validation validateV1(Map<String, Object> blueprint) {
        if (blueprint == null || blueprint.isEmpty()) {
            return new Validation(false, "", 0, "blueprint is null/empty");
        }

        String type = normalizeType(getStringAny(blueprint, "blueprint_type", "blueprintType", "type"));
        int version = getIntAny(blueprint, "blueprint_version", "blueprintVersion", "version");

        if (type.isBlank()) return new Validation(false, "", version, "missing blueprint_type");
        if (version != SUPPORTED_VERSION) {
            return new Validation(false, type, version, "unsupported blueprint_version=" + version + " (supported=" + SUPPORTED_VERSION + ")");
        }

        // Very lightweight structural checks by type (best-effort):
        // - castle: needs components list
        // - tulou/temple: needs overall_dimensions (optional) but no strict requirements
        if (type.equals("castle")) {
            Object comps = blueprint.get("components");
            if (!(comps instanceof java.util.List<?> list) || list.isEmpty()) {
                return new Validation(false, type, version, "castle blueprint requires non-empty components[]");
            }
        }

        return new Validation(true, type, version, null);
    }

    public static String normalizeType(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (s.isBlank()) return "";
        if (s.contains("castle") || s.contains("fort")) return "castle";
        if (s.contains("tulou") || s.contains("earthen") || s.contains("hakka")) return "tulou";
        if (s.contains("temple_of_heaven") || s.contains("tiantan") || (s.contains("temple") && s.contains("heaven")))
            return "temple_of_heaven";
        if (s.contains("great_wall") || s.contains("changcheng") || (s.contains("great") && s.contains("wall")))
            return "great_wall";
        if (s.contains("eiffel")) return "eiffel_tower";
        if (s.contains("golden_gate") || s.contains("goldengate") || s.contains("suspension_bridge")) return "golden_gate_bridge";
        if (s.contains("giant_wild_goose_pagoda") || s.contains("dayanta") || s.contains("pagoda")) return "giant_wild_goose_pagoda";
        return s;
    }

    private static String getStringAny(Map<String, Object> m, String... keys) {
        if (m == null || keys == null) return "";
        for (String k : keys) {
            if (k == null) continue;
            Object v = m.get(k);
            if (v == null) continue;
            String s = String.valueOf(v).trim();
            if (!s.isBlank()) return s;
        }
        return "";
    }

    private static int getIntAny(Map<String, Object> m, String... keys) {
        if (m == null || keys == null) return BlueprintSchema.SUPPORTED_VERSION;
        for (String k : keys) {
            if (k == null) continue;
            Object v = m.get(k);
            if (v == null) continue;
            if (v instanceof Number n) return n.intValue();
            try {
                String s = String.valueOf(v).trim();
                if (!s.isBlank()) return Integer.parseInt(s);
            } catch (Exception e) { LOG.debug("best-effort step failed", e); }
        }
        return BlueprintSchema.SUPPORTED_VERSION;
    }
}


