package com.formacraft.common.typology;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Server-side verified player hard constraints.
 * <p>
 * LLM-authored {@code design_sources} / bare {@code _server_verified_sources} are never trusted.
 * Only maps sealed by {@link #stampVerified} (after stripping untrusted input) grant
 * {@link TypologyParamResolver#SOURCE_USER_EXPLICIT}. The seal is an HMAC over the verified
 * map using a JVM-local secret, so copying the static prefix string cannot forge hard constraints.
 */
public final class UserConstraintContract {

    public static final String SERVER_VERIFIED_SOURCES_KEY = "_server_verified_sources";
    /** Opaque seal written only by {@link #stampVerified}; forged LLM copies without this are ignored. */
    public static final String SERVER_SEAL_KEY = "_fc_constraint_seal";
    public static final String PLAN_META_KEY = "user_constraint_contract";
    static final String SERVER_SEAL_PREFIX = "formacraft.server.seal.v2:";

    /** Process-local secret; never serialized into plans that LLM can see ahead of stamping. */
    private static final String SEAL_SECRET = UUID.randomUUID() + ":" + System.nanoTime();

    /**
     * Height in blocks: requires an explicit unit (格/块/m/米) or English {@code height}.
     * Rejects 「高28层」 and {@code height 100 floors} (storey count, not envelope height).
     * English digits require a full-number boundary so {@code height 100} cannot match as {@code 10}.
     */
    private static final Pattern HEIGHT_BLOCKS = Pattern.compile(
            "(?:高度|高)\\s*(\\d{1,3})\\s*[格块m米](?!\\s*层)"
                    + "|height\\s*[:=]?\\s*(\\d{1,3})(?!\\d)(?!\\s*(?:storeys?|floors?|层))",
            Pattern.CASE_INSENSITIVE);

    private UserConstraintContract() {}

    /** Remove any client/LLM-supplied verification markers before server re-stamping. */
    public static void stripUntrustedMarkers(Map<String, Object> params) {
        if (params == null) return;
        params.remove(SERVER_VERIFIED_SOURCES_KEY);
        params.remove(SERVER_SEAL_KEY);
    }

    /** True when the map carries a server-minted seal matching its verified sources. */
    public static boolean hasServerSeal(Map<String, Object> params) {
        if (params == null) return false;
        Object seal = params.get(SERVER_SEAL_KEY);
        if (seal == null) return false;
        Object raw = params.get(SERVER_VERIFIED_SOURCES_KEY);
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) return false;
        @SuppressWarnings("unchecked")
        Map<String, Object> verified = (Map<String, Object>) map;
        return computeSeal(verified).equals(String.valueOf(seal));
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
        params.put(SERVER_SEAL_KEY, computeSeal(verified));
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
     * Returns 0 when absent or when the cue is a storey count (「高28层」「height 100 floors」).
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

    static String computeSeal(Map<String, Object> verified) {
        if (verified == null || verified.isEmpty()) return SERVER_SEAL_PREFIX + "empty";
        TreeMap<String, String> ordered = new TreeMap<>();
        for (Map.Entry<String, Object> e : verified.entrySet()) {
            if (e.getKey() == null) continue;
            ordered.put(e.getKey(), String.valueOf(e.getValue()));
        }
        String payload = SEAL_SECRET + "|" + ordered;
        return SERVER_SEAL_PREFIX + sha256Hex(payload);
    }

    private static String sha256Hex(String payload) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // Extremely unlikely on a standard JVM; fall back to a non-forgeable process token.
            return Integer.toHexString(payload.hashCode()) + SEAL_SECRET.hashCode();
        }
    }
}
