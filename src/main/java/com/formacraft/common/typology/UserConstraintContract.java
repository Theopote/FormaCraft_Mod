package com.formacraft.common.typology;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
 * {@link TypologyParamResolver#SOURCE_USER_EXPLICIT}. The seal is a keyed SHA-256 digest over
 * verified field names, sources, and the sealed param values using a JVM-local secret.
 */
public final class UserConstraintContract {

    public static final String SERVER_VERIFIED_SOURCES_KEY = "_server_verified_sources";
    /** Opaque seal written only by {@link #stampVerified}; forged LLM copies without this are ignored. */
    public static final String SERVER_SEAL_KEY = "_fc_constraint_seal";
    public static final String PLAN_META_KEY = "user_constraint_contract";
    static final String SERVER_SEAL_PREFIX = "formacraft.server.seal.v2:";

    /** Process-local secret; never serialized into plans that LLM can see ahead of stamping. */
    private static final String SEAL_SECRET = UUID.randomUUID() + ":" + System.nanoTime();

    /** One player-authored height cue, optionally scoped to a building role hint. */
    public record HeightBinding(int blocks, String roleHint) {}

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

    /** True when the map carries a server-minted seal matching its verified sources and values. */
    public static boolean hasServerSeal(Map<String, Object> params) {
        if (params == null) return false;
        Object seal = params.get(SERVER_SEAL_KEY);
        if (seal == null) return false;
        Object raw = params.get(SERVER_VERIFIED_SOURCES_KEY);
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) return false;
        @SuppressWarnings("unchecked")
        Map<String, Object> verified = (Map<String, Object>) map;
        return computeSeal(verified, params).equals(String.valueOf(seal));
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
        params.put(SERVER_SEAL_KEY, computeSeal(verified, params));
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
     * Parse every explicit height-in-blocks cue with an optional role hint from nearby text
     * (主殿/正房 → hall, 亭/pavilion → pavilion). Storey counts are rejected.
     */
    public static List<HeightBinding> parseExplicitHeightBindings(String userText) {
        List<HeightBinding> out = new ArrayList<>();
        if (userText == null || userText.isBlank()) return out;
        Matcher m = HEIGHT_BLOCKS.matcher(userText);
        while (m.find()) {
            String g1 = m.group(1);
            String g2 = m.group(2);
            String raw = g1 != null ? g1 : g2;
            if (raw == null) continue;
            try {
                int n = Integer.parseInt(raw);
                // Secondary pavilions often sit at 6–12 blocks; allow from 4.
                if (n < 4 || n > 120) continue;
                String hint = inferHeightRoleHint(userText, m.start(), m.end());
                out.add(new HeightBinding(n, hint));
            } catch (NumberFormatException ignored) {
                // continue
            }
        }
        return out;
    }

    /**
     * Parse a single authoritative height for the primary mass.
     * Prefers a hall/main hint when multiple heights exist; otherwise the first match
     * (never the last — that incorrectly bound pavilion heights onto the main hall).
     */
    public static int parseExplicitHeightBlocks(String userText) {
        List<HeightBinding> all = parseExplicitHeightBindings(userText);
        if (all.isEmpty()) return 0;
        for (HeightBinding b : all) {
            if ("hall".equals(b.roleHint()) && b.blocks() >= 8) return b.blocks();
        }
        for (HeightBinding b : all) {
            if (b.roleHint() == null && b.blocks() >= 8) return b.blocks();
        }
        return all.getFirst().blocks() >= 8 ? all.getFirst().blocks() : 0;
    }

    /** True when player text contains an explicit numeric height-in-blocks cue. */
    public static boolean heightExplicitInText(String userText) {
        return !parseExplicitHeightBindings(userText).isEmpty();
    }

    /**
     * Bind player-authored height onto params: overwrite LLM height with the authoritative value
     * and seal as USER_EXPLICIT. No-op when the text has no block height cue.
     */
    public static void applyAuthoritativeHeight(Map<String, Object> params, String userText) {
        applyAuthoritativeHeightValue(params, parseExplicitHeightBlocks(userText));
    }

    /** Bind a concrete height value and seal height/towerHeight. */
    public static void applyAuthoritativeHeightValue(Map<String, Object> params, int heightBlocks) {
        if (params == null || heightBlocks <= 0) return;
        stripUntrustedMarkers(params);
        params.put("height", heightBlocks);
        params.put("towerHeight", heightBlocks);
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
        List<HeightBinding> bindings = parseExplicitHeightBindings(userText);
        if (!bindings.isEmpty()) {
            List<Map<String, Object>> constraints = new ArrayList<>(bindings.size());
            for (HeightBinding b : bindings) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("field", "height");
                row.put("value", b.blocks());
                row.put("source", TypologyParamResolver.SOURCE_USER_EXPLICIT);
                if (b.roleHint() != null) row.put("target_role", b.roleHint());
                constraints.add(row);
            }
            out.put("constraints", constraints);
            int primary = parseExplicitHeightBlocks(userText);
            if (primary > 0) {
                out.put("height_blocks", primary);
                out.put("height_source", TypologyParamResolver.SOURCE_USER_EXPLICIT);
            }
        }
        return out;
    }

    public static String normalizeClass(String cls) {
        if (cls == null) return "generic";
        return cls.trim().toLowerCase(Locale.ROOT);
    }

    static String inferHeightRoleHint(String text, int matchStart, int matchEnd) {
        if (text == null) return null;
        // Restrict to the local clause so "高24格的主殿，…高10格的木亭" does not bleed roles.
        // Chinese顿号 、, newlines, and parentheses are also clause boundaries.
        int from = matchStart;
        while (from > 0) {
            char c = text.charAt(from - 1);
            if (isHeightClauseBoundary(c)) break;
            from--;
        }
        int to = matchEnd;
        while (to < text.length()) {
            char c = text.charAt(to);
            if (isHeightClauseBoundary(c)) break;
            to++;
        }
        String window = text.substring(from, to).toLowerCase(Locale.ROOT);
        // Hall/main before pavilion — a clause should not inherit a later annex type.
        if (window.contains("主殿") || window.contains("正殿") || window.contains("正房")
                || window.contains("大殿") || window.contains("主楼") || window.contains("主厅")
                || window.contains("hall") || window.contains("main")) {
            return "hall";
        }
        if (window.contains("亭") || window.contains("pavilion") || window.contains("廊")) {
            return "pavilion";
        }
        if (window.contains("塔") || window.contains("pagoda") || window.contains("tower")) {
            return "tower";
        }
        return null;
    }

    /** Sentence / list separators that bound a height cue's local role window. */
    private static boolean isHeightClauseBoundary(char c) {
        return c == '，' || c == ',' || c == '；' || c == ';' || c == '。' || c == '.' || c == '!'
                || c == '、' || c == '\n' || c == '\r'
                || c == '（' || c == '）' || c == '(' || c == ')'
                || c == '【' || c == '】' || c == '[' || c == ']';
    }

    static String computeSeal(Map<String, Object> verified, Map<String, Object> params) {
        if (verified == null || verified.isEmpty()) return SERVER_SEAL_PREFIX + "empty";
        TreeMap<String, String> ordered = new TreeMap<>();
        for (Map.Entry<String, Object> e : verified.entrySet()) {
            if (e.getKey() == null) continue;
            ordered.put("src:" + e.getKey(), String.valueOf(e.getValue()));
            if (params != null && params.containsKey(e.getKey())) {
                ordered.put("val:" + e.getKey(), String.valueOf(params.get(e.getKey())));
            }
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
