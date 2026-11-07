package com.formacraft.common.typology;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Server-side verified player hard constraints.
 * <p>
 * LLM-authored {@code design_sources} / bare {@code _server_verified_sources} are never trusted.
 * Only maps sealed by {@link #stampVerified} (after stripping untrusted input) grant
 * {@link TypologyParamResolver#SOURCE_USER_EXPLICIT}.
 */
public final class UserConstraintContract {

    public static final String SERVER_VERIFIED_SOURCES_KEY = "_server_verified_sources";
    /** Opaque seal written only by {@link #stampVerified}; forged LLM copies without this are ignored. */
    public static final String SERVER_SEAL_KEY = "_fc_constraint_seal";
    public static final String PLAN_META_KEY = "user_constraint_contract";
    static final String SERVER_SEAL_VALUE = "formacraft.server.seal.v1";

    /**
     * Height in blocks: requires an explicit unit (格/块/m/米) or English {@code height}.
     * Rejects 「高28层」 (storey count, not envelope height).
     */
    private static final Pattern HEIGHT_BLOCKS = Pattern.compile(
            "(?:高度|高)\\s*(\\d{1,3})\\s*(?:格|块|m|米)(?!\\s*层)"
                    + "|(?:height)\\s*[:=]?\\s*(\\d{1,3})(?!\\s*(?:storeys?|floors?|层))",
            Pattern.CASE_INSENSITIVE);

    private UserConstraintContract() {}

    /** Remove any client/LLM-supplied verification markers before server re-stamping. */
    public static void stripUntrustedMarkers(Map<String, Object> params) {
        if (params == null) return;
        params.remove(SERVER_VERIFIED_SOURCES_KEY);
        params.remove(SERVER_SEAL_KEY);
    }

    /** True when the map carries a server seal (not a bare forged verified map). */
    public static boolean hasServerSeal(Map<String, Object> params) {
        if (params == null) return false;
        Object seal = params.get(SERVER_SEAL_KEY);
        return seal != null && SERVER_SEAL_VALUE.equals(String.valueOf(seal));
    }

    /** Stamp server-verified USER_EXPLICIT onto param keys (trusted hard constraints). */
    public static void stampVerified(Map<String, Object> params, String... keys) {
        if (params == null || keys == null) return;
        @SuppressWarnings("unchecked")
        Map<String, Object> verified = params.get(SERVER_VERIFIED_SOURCES_KEY) instanceof Map<?, ?> existing
                && hasServerSeal(params)
                ? new HashMap<>((Map<String, Object>) existing)
                : new HashMap<>();
        for (String key : keys) {
            if (key == null || key.isBlank()) continue;
            verified.put(key, TypologyParamResolver.SOURCE_USER_EXPLICIT);
        }
        params.put(SERVER_VERIFIED_SOURCES_KEY, verified);
        params.put(SERVER_SEAL_KEY, SERVER_SEAL_VALUE);
    }

    public static boolean isServerVerified(Map<String, Object> params, String key) {
        if (params == null || key == null) return false;
        if (!hasServerSeal(params)) return false;
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
     * Parse an explicit height in blocks from player text (「高28格」「高度 40米」「height:24」).
     * Returns 0 when absent or when the cue is a storey count (「高28层」).
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

    /** True when player text contains an explicit numeric height-in-blocks cue. */
    public static boolean heightExplicitInText(String userText) {
        return parseExplicitHeightBlocks(userText) > 0;
    }

    /**
     * Bind player-authored height onto params: overwrite LLM height with the authoritative value
     * and seal as USER_EXPLICIT. No-op when the text has no block height cue.
     */
    public static void applyAuthoritativeHeight(Map<String, Object> params, String userText) {
        if (params == null) return;
        stripUntrustedMarkers(params);
        int h = parseExplicitHeightBlocks(userText);
        if (h <= 0) return;
        params.put("height", h);
        params.put("towerHeight", h);
        stampVerified(params, "height", "towerHeight");
    }

    /**
     * When user text authorizes an explicit height, stamp verified sources onto the param map
     * after aligning the numeric value to the player-authored height.
     */
    public static void stampHeightFromUserText(Map<String, Object> params, String userText) {
        applyAuthoritativeHeight(params, userText);
    }

    /** Build a plan_meta contract snapshot from server user text (not from LLM params). */
    public static Map<String, Object> buildPlanMetaContract(String userText) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", "formacraft.user_constraint_contract.v1");
        out.put("source", "server_user_text");
        int h = parseExplicitHeightBlocks(userText);
        if (h > 0) {
            out.put("height_blocks", h);
            out.put("height_source", TypologyParamResolver.SOURCE_USER_EXPLICIT);
        }
        return out;
    }

    public static String normalizeClass(String cls) {
        if (cls == null) return "generic";
        return cls.trim().toLowerCase(Locale.ROOT);
    }
}
