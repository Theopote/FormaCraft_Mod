package com.formacraft.common.typology;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Server-side verified player hard constraints. LLM-authored {@code design_sources=USER_EXPLICIT}
 * is not trusted until stamped here (or into {@link #SERVER_VERIFIED_SOURCES_KEY} on a param map).
 */
public final class UserConstraintContract {

    public static final String SERVER_VERIFIED_SOURCES_KEY = "_server_verified_sources";

    private static final Pattern HEIGHT_BLOCKS = Pattern.compile(
            "(?:高度|高)\\s*(\\d{1,3})\\s*(?:格|块|m|米)?|(?:height)\\s*[:=]?\\s*(\\d{1,3})",
            Pattern.CASE_INSENSITIVE);

    private UserConstraintContract() {}

    /** Stamp server-verified USER_EXPLICIT onto param keys (trusted hard constraints). */
    public static void stampVerified(Map<String, Object> params, String... keys) {
        if (params == null || keys == null) return;
        @SuppressWarnings("unchecked")
        Map<String, Object> verified = params.get(SERVER_VERIFIED_SOURCES_KEY) instanceof Map<?, ?> existing
                ? new HashMap<>((Map<String, Object>) existing)
                : new HashMap<>();
        for (String key : keys) {
            if (key == null || key.isBlank()) continue;
            verified.put(key, TypologyParamResolver.SOURCE_USER_EXPLICIT);
        }
        params.put(SERVER_VERIFIED_SOURCES_KEY, verified);
    }

    public static boolean isServerVerified(Map<String, Object> params, String key) {
        if (params == null || key == null) return false;
        Object raw = params.get(SERVER_VERIFIED_SOURCES_KEY);
        if (!(raw instanceof Map<?, ?> map)) return false;
        Object v = map.get(key);
        if (v == null && ("height".equals(key) || "towerHeight".equals(key))) {
            v = map.containsKey("height") ? map.get("height") : map.get("towerHeight");
        }
        return v != null && TypologyParamResolver.SOURCE_USER_EXPLICIT.equalsIgnoreCase(
                String.valueOf(v).trim());
    }

    /**
     * Parse an explicit height from player text (「高28格」「高度 40」). Returns 0 when absent.
     */
    public static int parseExplicitHeightBlocks(String userText) {
        if (userText == null || userText.isBlank()) return 0;
        Matcher m = HEIGHT_BLOCKS.matcher(userText);
        int found = 0;
        while (m.find()) {
            String g1 = m.group(1);
            String g2 = m.group(2);
            String raw = g1 != null ? g1 : g2;
            if (raw == null) continue;
            try {
                int n = Integer.parseInt(raw);
                if (n >= 8 && n <= 120) found = n;
            } catch (NumberFormatException ignored) {
                // continue
            }
        }
        return found;
    }

    /** True when player text contains an explicit numeric height cue. */
    public static boolean heightExplicitInText(String userText) {
        return parseExplicitHeightBlocks(userText) > 0;
    }

    /**
     * When user text authorizes an explicit height, stamp verified sources onto the param map
     * if it already carries a height value (typically after LLM fill).
     */
    public static void stampHeightFromUserText(Map<String, Object> params, String userText) {
        if (params == null || !heightExplicitInText(userText)) return;
        if (params.containsKey("height") || params.containsKey("towerHeight")) {
            stampVerified(params, "height", "towerHeight");
        }
    }

    public static String normalizeClass(String cls) {
        if (cls == null) return "generic";
        return cls.trim().toLowerCase(Locale.ROOT);
    }
}
