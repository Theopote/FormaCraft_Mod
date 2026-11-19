package com.formacraft.common.typology;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
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

    /**
     * One player-authored height cue, optionally scoped to a building role hint.
     * {@code sourceText} is the local clause window (side cues like 西厢房); {@code namedKeys}
     * preserves shared identity keys (wing) that roleHint may collapse (wing→pavilion).
     * {@code envelopeMode} is {@code shaft} (MASS only) or {@code building} (incl. roof/finial),
     * inferred only from this clause — never from another building's wording in the same request.
     */
    public record HeightBinding(
            int blocks,
            String roleHint,
            String sourceText,
            List<String> namedKeys,
            String envelopeMode,
            boolean modeExplicit) {
        public HeightBinding {
            namedKeys = namedKeys == null ? List.of() : List.copyOf(namedKeys);
            envelopeMode = normalizeEnvelopeMode(envelopeMode);
        }

        public HeightBinding(int blocks, String roleHint) {
            this(blocks, roleHint, null, List.of(), "shaft", false);
        }

        public HeightBinding(int blocks, String roleHint, String sourceText, List<String> namedKeys) {
            this(blocks, roleHint, sourceText, namedKeys, "shaft", false);
        }

        public HeightBinding(
                int blocks, String roleHint, String sourceText, List<String> namedKeys, String envelopeMode) {
            this(blocks, roleHint, sourceText, namedKeys, envelopeMode, isEnvelopeModeExplicit(sourceText));
        }
    }

    /** Normalize to {@code shaft} or {@code building}. */
    public static String normalizeEnvelopeMode(String mode) {
        return "building".equals(mode) ? "building" : "shaft";
    }

    /**
     * True when the local clause explicitly names shaft or whole-building semantics.
     * Bare 「高度 N 格」is a default shaft inference — not an explicit mode declaration.
     */
    public static boolean isEnvelopeModeExplicit(String clause) {
        if (clause == null || clause.isBlank()) return false;
        String t = clause.toLowerCase(Locale.ROOT);
        return clause.contains("主体高度") || clause.contains("塔身高度") || clause.contains("塔身")
                || t.contains("shaft height") || t.contains("shaft only")
                || clause.contains("整座") || clause.contains("整栋") || clause.contains("整体高度")
                || clause.contains("含屋顶")
                || t.contains("whole-building") || t.contains("whole building")
                || t.contains("including the roof") || t.contains("including roof");
    }

    /**
     * Local-clause height semantics. 「整座/含屋顶」→ building; 「主体/塔身」→ shaft.
     * Classification: HARD_CONSTRAINT height semantics from explicit player phrasing.
     */
    public static String inferEnvelopeMode(String clause) {
        if (clause == null || clause.isBlank()) return "shaft";
        String t = clause.toLowerCase(Locale.ROOT);
        // Shaft-explicit wins inside the same clause over whole-building cues.
        if (clause.contains("主体高度") || clause.contains("塔身高度") || clause.contains("塔身")
                || t.contains("shaft height") || t.contains("shaft only")) {
            return "shaft";
        }
        if (clause.contains("整座") || clause.contains("整栋") || clause.contains("整体高度")
                || clause.contains("含屋顶")
                || t.contains("whole-building") || t.contains("whole building")
                || t.contains("including the roof") || t.contains("including roof")) {
            return "building";
        }
        return "shaft";
    }

    /**
     * One player-authored design omission (roof / windows / entrance), optionally role/ordinal-scoped.
     * Each text match is an independent declaration (span + sourceText) — never merge by domain.
     * {@code roleHint} may be {@code all_main_masses} / {@code multi_named} for verified multi-target seals.
     * Classification: HARD_CONSTRAINT — server re-verifies from real player text before sealing.
     */
    public record DesignOmitBinding(
            String domain,
            String roleHint,
            String ordinalScope,
            String sourceText,
            int matchStart,
            int matchEnd,
            List<String> namedKeys
    ) {
        public DesignOmitBinding {
            namedKeys = namedKeys == null ? List.of() : List.copyOf(namedKeys);
        }

        public DesignOmitBinding(String domain, String roleHint) {
            this(domain, roleHint, null, null, -1, -1, List.of());
        }

        public DesignOmitBinding(String domain, String roleHint, String ordinalScope) {
            this(domain, roleHint, ordinalScope, null, -1, -1, List.of());
        }
    }

    private static final Pattern ORDINAL_BUILDING = Pattern.compile(
            "第([一二两三四五六七八九十]|\\d+)栋");
    /** Explicit whole-compound omit/height quantifiers — mirrors Python _EXPLICIT_ALL_MASS_QUANTIFIERS. */
    private static final Pattern ALL_MASS_QUANTIFIER = Pattern.compile(
            "所有建筑|全部建筑|两栋均|两栋都|每栋均|每栋都|全部主体|所有主体|所有房子|全部房子");
    private static final Pattern JOINT_MULTI_SUBJECT = Pattern.compile(
            "(?:都|均|全部|一起|皆)(?:要|采用|使用|改成|改用|设置|不要|不需要|不需)?");
    private static final Map<String, Integer> CN_ORDINAL = Map.ofEntries(
            Map.entry("一", 1), Map.entry("二", 2), Map.entry("两", 2), Map.entry("三", 3),
            Map.entry("四", 4), Map.entry("五", 5), Map.entry("六", 6), Map.entry("七", 7),
            Map.entry("八", 8), Map.entry("九", 9), Map.entry("十", 10));

    /**
     * Height in blocks: requires an explicit unit (格/块/m/米) or English {@code height}.
     * Rejects 「高28层」 and {@code height 100 floors} (storey count, not envelope height).
     * English digits require a full-number boundary so {@code height 100} cannot match as {@code 10}.
     */
    private static final Pattern HEIGHT_BLOCKS = Pattern.compile(
            "(?:高度|高)\\s*(\\d{1,3})\\s*[格块m米](?!\\s*层)"
                    + "|height\\s*[:=]?\\s*(\\d{1,3})(?!\\d)(?!\\s*(?:storeys?|floors?|层))",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DESIGN_OMIT_ROOF = Pattern.compile(
            "(?:不要|不需要|不需)(?:生成|添加|建造)?屋顶|无屋顶");
    private static final Pattern DESIGN_OMIT_WINDOW = Pattern.compile(
            "(?:不要|不需要|不需)(?:生成|添加)?(?:窗户|窗洞)|不开窗|无窗(?:户)?");
    private static final Pattern DESIGN_OMIT_ENTRANCE = Pattern.compile(
            "(?:不要|不需要|不需)(?:生成|添加)?(?:入口|门洞)|无入口");

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
                // Same clause window as omit — keeps 西厢房 beside 高度12格 for side-local binds.
                String clause = omitClauseWindow(userText, m.start(), m.end());
                List<String> keys = namedKeysForConstraintWindow(userText, m.start(), m.end());
                String mode = inferEnvelopeMode(clause);
                out.add(new HeightBinding(n, hint, clause, keys, mode, isEnvelopeModeExplicit(clause)));
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
        if (params == null) return;
        stripUntrustedMarkers(params);
        applyAuthoritativeHeightValue(params, parseExplicitHeightBlocks(userText));
    }

    /** Bind a concrete height value and seal height/towerHeight (caller must strip forgeries first). */
    public static void applyAuthoritativeHeightValue(Map<String, Object> params, int heightBlocks) {
        if (params == null || heightBlocks <= 0) return;
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

    /**
     * Re-mint seals for previously verified keys after a strip, when the values are still present.
     * Used when compile re-enters {@code RequestIntentInjector} without a FormaRequest text.
     */
    public static void restampPreserved(Map<String, Object> params, Map<String, Object> priorVerified) {
        if (params == null || priorVerified == null || priorVerified.isEmpty()) return;
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, Object> e : priorVerified.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank()) continue;
            if (!params.containsKey(e.getKey()) || params.get(e.getKey()) == null) continue;
            if (e.getValue() != null
                    && TypologyParamResolver.SOURCE_USER_EXPLICIT.equalsIgnoreCase(
                    String.valueOf(e.getValue()).trim())) {
                keys.add(e.getKey());
            }
        }
        if (!keys.isEmpty()) {
            stampVerified(params, keys.toArray(String[]::new));
        }
    }

    /**
     * Parse local roof/window/entrance omission cues with clause-local role hints.
     * Does not invent ownership from the whole prompt — roleHint may be null.
     */
    public static List<DesignOmitBinding> parseDesignOmitBindings(String userText) {
        List<DesignOmitBinding> out = new ArrayList<>();
        if (userText == null || userText.isBlank()) return out;
        collectOmit(out, DESIGN_OMIT_ROOF, userText, "roof");
        collectOmit(out, DESIGN_OMIT_WINDOW, userText, "window");
        collectOmit(out, DESIGN_OMIT_ENTRANCE, userText, "entrance");
        return out;
    }

    private static void collectOmit(
            List<DesignOmitBinding> out, Pattern pattern, String text, String domain) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            // Double negation / meta-negation: 「不是无窗」「并非无屋顶」must not mint OMITTED.
            if (!omitCueAuthorized(text, m.start())) continue;
            // Skip gable-window clauses for window domain (山墙…无窗).
            if ("window".equals(domain)) {
                int from = Math.max(0, m.start() - 30);
                String prefix = text.substring(from, m.start());
                if (prefix.matches("(?s).*山墙[^。；\\n]*")) continue;
            }
            OmitScopeInference scope = inferOmitScope(text, m.start(), m.end());
            String ordinal = scope.roleHint() != null && scope.roleHint().startsWith("building_")
                    ? scope.roleHint() : null;
            // Clause window keeps 西厢房 visible beside 不要屋顶 for side-local wing binds.
            String clause = omitClauseWindow(text, m.start(), m.end());
            out.add(new DesignOmitBinding(
                    domain, scope.roleHint(), ordinal, clause, m.start(), m.end(), scope.namedKeys()));
        }
    }

    /**
     * Expand omit/height match to the surrounding clause (parity with Python _span_clause_window
     * and _roof_clause_window). Commas and顿号 bound independent sibling clauses so
     * 「西厢房不要屋顶，东厢房不要屋顶」does not merge side cues.
     */
    static String omitClauseWindow(String text, int matchStart, int matchEnd) {
        if (text == null || text.isEmpty()) return "";
        int from = Math.max(0, Math.min(text.length(), matchStart));
        int to = Math.max(from, Math.min(text.length(), matchEnd));
        while (from > 0) {
            char c = text.charAt(from - 1);
            if (isOmitClauseBoundary(c)) {
                break;
            }
            from--;
        }
        while (to < text.length()) {
            char c = text.charAt(to);
            if (isOmitClauseBoundary(c)) {
                break;
            }
            to++;
        }
        return text.substring(from, to);
    }

    /** Clause separators shared with height windows and Python roof/omit span bounds. */
    private static boolean isOmitClauseBoundary(char c) {
        return c == '。' || c == '；' || c == '！' || c == '？' || c == '\n' || c == '\r'
                || c == '.' || c == '!' || c == '?' || c == ';'
                || c == '，' || c == ',' || c == '、';
    }

    /** Named keys visible in the same local window used for omit/height host identity. */
    static List<String> namedKeysForConstraintWindow(String text, int matchStart, int matchEnd) {
        if (text == null) return List.of();
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
        String window = text.substring(from, to);
        List<String> namedKeys = namedKeysInWindow(window);
        if (namedKeys.size() > 1 && JOINT_MULTI_SUBJECT.matcher(window).find()) {
            return namedKeys;
        }
        if (namedKeys.size() == 1) return namedKeys;
        if (namedKeys.size() > 1) {
            String nearest = nearestNamedKey(window, matchStart - from);
            return nearest == null ? List.of() : List.of(nearest);
        }
        return List.of();
    }

    private record OmitScopeInference(String roleHint, List<String> namedKeys) {
        private OmitScopeInference {
            namedKeys = namedKeys == null ? List.of() : List.copyOf(namedKeys);
        }
    }

    /**
     * Reject omit cues wrapped in outer negation (不是无窗 / 并非无屋顶 / 不能没有…).
     * Classification: FORMAT_REPAIR — preserves player affirmation of openings/roofs.
     */
    static boolean omitCueAuthorized(String text, int matchStart) {
        if (text == null || matchStart <= 0) return true;
        String prefix = text.substring(Math.max(0, matchStart - 16), matchStart);
        if (Pattern.compile("(?:不是|并非|绝非|绝不是)\\s*$").matcher(prefix).find()) {
            return false;
        }
        if (Pattern.compile("(?:不能|不会|别)没有\\s*$").matcher(prefix).find()) {
            return false;
        }
        return true;
    }

    /**
     * Prefer ordinal {@code building_N}; else all-mass quantifier / multi_named; else nearest role.
     * 「主殿旁的亭子不要屋顶」binds pavilion; 「所有建筑都不要屋顶」→ all_main_masses.
     */
    static String inferDesignOmitRoleHint(String text, int matchStart, int matchEnd) {
        return inferOmitScope(text, matchStart, matchEnd).roleHint();
    }

    /**
     * Shared omit-scope inference for collectOmit and tests.
     * Classification: HARD_CONSTRAINT host identity — no DESIGN_OVERRIDE from component order.
     */
    static OmitScopeInference inferOmitScope(String text, int matchStart, int matchEnd) {
        if (text == null) return new OmitScopeInference(null, List.of());
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
        String window = text.substring(from, to);
        Matcher ordinal = ORDINAL_BUILDING.matcher(window);
        if (ordinal.find()) {
            String raw = ordinal.group(1);
            Integer mapped = CN_ORDINAL.get(raw);
            int n = mapped != null ? mapped : -1;
            if (n < 0) {
                try {
                    n = Integer.parseInt(raw);
                } catch (NumberFormatException ex) {
                    n = -1;
                }
            }
            if (n > 0) return new OmitScopeInference("building_" + n, List.of());
        }
        if (ALL_MASS_QUANTIFIER.matcher(window).find()) {
            return new OmitScopeInference("all_main_masses", List.of());
        }
        List<String> namedKeys = namedKeysInWindow(window);
        if (namedKeys.size() > 1 && JOINT_MULTI_SUBJECT.matcher(window).find()) {
            return new OmitScopeInference("multi_named", namedKeys);
        }
        if (namedKeys.size() == 1) {
            String key = namedKeys.getFirst();
            return new OmitScopeInference(roleHintForNamedKey(key), List.of(key));
        }
        if (namedKeys.size() > 1) {
            // 「办公楼旁的锅炉房不要屋顶」→ nearest full namedKey, never hall/pavilion/tower-only.
            String nearest = nearestNamedKey(window, matchStart - from);
            if (nearest != null) {
                return new OmitScopeInference(roleHintForNamedKey(nearest), List.of(nearest));
            }
            return new OmitScopeInference(null, List.of());
        }
        return new OmitScopeInference(nearestRoleHint(window, matchStart - from), List.of());
    }

    /**
     * Shared named-building identity table — mirrors Python {@code NAMED_BUILDINGS}.
     * Each key is an independent identity; do not collapse office/keep/residence into hall.
     */
    private static final String[][] NAMED_BUILDING_ALIASES = {
            {"main_body", "主楼", "main_body", "main_building"},
            {"keep", "主堡", "keep"},
            {"tower", "塔楼", "钟楼", "pagoda", "tower"},
            {"office", "办公楼", "office"},
            {"workshop", "主车间", "维修车间", "workshop"},
            {"boiler", "锅炉房", "boiler"},
            {"main_hall", "正厅", "中央大厅", "主殿", "正殿", "大殿", "main_hall", "townhall_main"},
            {"pavilion", "凉亭", "亭子", "小亭", "水上亭", "水榭", "八角亭", "六角亭", "方亭",
                    "garden_pavilion", "pavilion"},
            {"wing", "厢房", "侧翼", "east_wing", "west_wing"},
            {"gatehouse", "门楼", "gatehouse"},
            {"mill", "磨坊", "mill"},
            {"warehouse", "仓库", "warehouse"},
            {"reception", "接待楼", "reception"},
            {"research", "研究楼", "research"},
            {"dispatch", "调度楼", "dispatch"},
            {"residence", "居住楼", "residence"},
            {"chapel", "礼拜堂", "chapel"},
    };

    /** Named building keys present in window (full shared identity table). */
    static List<String> namedKeysInWindow(String window) {
        if (window == null || window.isBlank()) return List.of();
        String lower = window.toLowerCase(Locale.ROOT);
        List<String> found = new ArrayList<>();
        for (String[] group : NAMED_BUILDING_ALIASES) {
            String key = group[0];
            for (int i = 1; i < group.length; i++) {
                if (lower.contains(group[i].toLowerCase(Locale.ROOT))) {
                    if (!found.contains(key)) found.add(key);
                    break;
                }
            }
        }
        return found;
    }

    /** Aliases for one named key (includes the key itself). Unknown keys return just the key. */
    public static List<String> aliasesForNamedKey(String namedKey) {
        if (namedKey == null || namedKey.isBlank()) return List.of();
        String key = namedKey.trim().toLowerCase(Locale.ROOT);
        for (String[] group : NAMED_BUILDING_ALIASES) {
            if (key.equals(group[0])) {
                List<String> out = new ArrayList<>(group.length);
                for (String alias : group) {
                    if (alias != null && !alias.isBlank()) out.add(alias);
                }
                return List.copyOf(out);
            }
        }
        return List.of(key);
    }

    /**
     * Map shared named-building keys onto classic role hints where applicable.
     * {@code wing} stays {@code wing} — never borrow pavilion (凉亭) as wing identity.
     * Classification: HARD_CONSTRAINT host identity.
     */
    private static String roleHintForNamedKey(String key) {
        if (key == null) return null;
        return switch (key) {
            case "main_hall", "main_body" -> "hall";
            case "pavilion" -> "pavilion";
            case "wing" -> "wing";
            case "tower" -> "tower";
            default -> key;
        };
    }

    /**
     * Nearest full named-building identity (office/boiler/workshop/…).
     * Equal-distance distinct keys → null (capability gap, no guess).
     */
    static String nearestNamedKey(String window, int relToken) {
        if (window == null || window.isBlank()) return null;
        String lower = window.toLowerCase(Locale.ROOT);
        record Marker(String key, int dist) {}
        List<Marker> hits = new ArrayList<>();
        for (String[] group : NAMED_BUILDING_ALIASES) {
            String key = group[0];
            for (int i = 1; i < group.length; i++) {
                String n = group[i].toLowerCase(Locale.ROOT);
                int fromIdx = 0;
                while (true) {
                    int idx = lower.indexOf(n, fromIdx);
                    if (idx < 0) break;
                    hits.add(new Marker(key, Math.abs(idx - relToken)));
                    fromIdx = idx + Math.max(1, n.length());
                }
            }
        }
        if (hits.isEmpty()) return null;
        hits.sort(Comparator.comparingInt(Marker::dist));
        int best = hits.getFirst().dist();
        String key = hits.getFirst().key();
        for (Marker m : hits) {
            if (m.dist() > best) break;
            if (!key.equals(m.key())) return null;
        }
        return key;
    }

    /**
     * Role nearest the constraint token wins. Dual markers at equal distance → null (no guess).
     * Prefers full named-building keys; falls back to short hall/pavilion/tower cues (亭/塔).
     * Never uses bare {@code main} — that false-matches {@code MASS_MAIN}.
     */
    private static String nearestRoleHint(String window, int relToken) {
        String named = nearestNamedKey(window, relToken);
        if (named != null) {
            return roleHintForNamedKey(named);
        }
        if (window == null || window.isBlank()) return null;
        String lower = window.toLowerCase(Locale.ROOT);
        record Marker(String role, int dist) {}
        List<Marker> hits = new ArrayList<>();
        String[][] groups = {
                {"hall", "主殿", "正殿", "正房", "大殿", "主楼", "主厅", "main_hall", "townhall", "hall"},
                {"pavilion", "凉亭", "亭子", "小亭", "水上亭", "水榭", "八角亭", "六角亭", "方亭",
                        "garden_pavilion", "pavilion", "亭"},
                {"tower", "塔楼", "钟楼", "pagoda", "tower", "塔"}
        };
        for (String[] group : groups) {
            String role = group[0];
            for (int i = 1; i < group.length; i++) {
                String n = group[i].toLowerCase(Locale.ROOT);
                int fromIdx = 0;
                while (true) {
                    int idx = lower.indexOf(n, fromIdx);
                    if (idx < 0) break;
                    hits.add(new Marker(role, Math.abs(idx - relToken)));
                    fromIdx = idx + Math.max(1, n.length());
                }
            }
        }
        if (hits.isEmpty()) return null;
        hits.sort(Comparator.comparingInt(Marker::dist));
        int best = hits.getFirst().dist();
        String role = hits.getFirst().role();
        for (Marker m : hits) {
            if (m.dist() > best) break;
            if (!role.equals(m.role())) return null;
        }
        return role;
    }

    /**
     * Apply a verified local omission onto mass params and seal the relevant keys.
     * Classification: HARD_CONSTRAINT — values come from server-checked player text.
     */
    public static void applyVerifiedDesignOmit(Map<String, Object> params, String domain) {
        if (params == null || domain == null) return;
        switch (domain) {
            case "roof" -> {
                params.put("roof_type", "none");
                params.put("roof_requirement", "OMITTED");
                stampVerified(params, "roof_type", "roof_requirement");
            }
            case "window" -> {
                params.put("window_style", "none");
                params.put("window_ratio", 0);
                params.put("facade_requirement", "OMITTED");
                params.put("window_requirement", "OMITTED");
                stampVerified(params, "window_style", "window_ratio",
                        "facade_requirement", "window_requirement");
            }
            case "entrance" -> {
                params.put("entrance_type", "none");
                params.put("entrance_requirement", "OMITTED");
                stampVerified(params, "entrance_type", "entrance_requirement");
            }
            default -> {
            }
        }
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

    /**
     * Same nearest-role rule as omit bindings — 「主殿旁边的亭子高度6格」→ pavilion, not hall.
     * Classification: HARD_CONSTRAINT host identity.
     */
    static String inferHeightRoleHint(String text, int matchStart, int matchEnd) {
        if (text == null) return null;
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
        String window = text.substring(from, to);
        Matcher ordinal = ORDINAL_BUILDING.matcher(window);
        if (ordinal.find()) {
            String raw = ordinal.group(1);
            Integer mapped = CN_ORDINAL.get(raw);
            int n = mapped != null ? mapped : -1;
            if (n < 0) {
                try {
                    n = Integer.parseInt(raw);
                } catch (NumberFormatException ex) {
                    n = -1;
                }
            }
            if (n > 0) return "building_" + n;
        }
        List<String> namedKeys = namedKeysInWindow(window);
        if (namedKeys.size() == 1) {
            return roleHintForNamedKey(namedKeys.getFirst());
        }
        if (namedKeys.size() > 1) {
            String nearest = nearestNamedKey(window, matchStart - from);
            if (nearest != null) {
                return roleHintForNamedKey(nearest);
            }
            return null;
        }
        return nearestRoleHint(window, matchStart - from);
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

    /**
     * Server seal for stacked envelope plan_meta rows (family / mass_ids / target / mode).
     * A plain {@code trusted=true} boolean is never sufficient.
     */
    public static String sealStackedEnvelopeTargets(List<Map<String, Object>> rows) {
        return SERVER_SEAL_PREFIX + "env:" + sha256Hex(SEAL_SECRET + "|stacked|" + canonicalEnvelopeRows(rows));
    }

    /** True when {@code seal} matches the canonical form of {@code rows}. */
    public static boolean verifyStackedEnvelopeTargets(List<?> rows, Object seal) {
        if (seal == null || rows == null) return false;
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> m)) return false;
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() == null) continue;
                String k = String.valueOf(e.getKey());
                // Ignore non-authoritative flags when verifying.
                if ("trusted".equals(k)) continue;
                copy.put(k, e.getValue());
            }
            normalized.add(copy);
        }
        return sealStackedEnvelopeTargets(normalized).equals(String.valueOf(seal));
    }

    private static String canonicalEnvelopeRows(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> row : rows) {
            if (row == null) continue;
            TreeMap<String, String> ordered = new TreeMap<>();
            for (Map.Entry<String, Object> e : row.entrySet()) {
                if (e.getKey() == null || "trusted".equals(e.getKey())) continue;
                Object v = e.getValue();
                if (v instanceof List<?> list) {
                    ordered.put(e.getKey(), list.stream().map(String::valueOf).sorted()
                            .reduce((a, b) -> a + "," + b).orElse(""));
                } else {
                    ordered.put(e.getKey(), String.valueOf(v));
                }
            }
            sb.append(ordered).append(';');
        }
        return sb.toString();
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
