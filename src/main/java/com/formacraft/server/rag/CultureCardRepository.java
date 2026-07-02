package com.formacraft.server.rag;

import com.formacraft.common.logging.FcaLog;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Loads culture cards and example JSONs from mod resources.
 *
 * P0: uses FabricLoader ModContainer.findPath so it works both in-dev and in-jar.
 */
public final class CultureCardRepository {
    private static final FcaLog LOG = FcaLog.of("CultureCardRepository");
    private static final Gson GSON = new GsonBuilder().create();
    private static final String MOD_ID = "formacraft";

    private final List<CultureCard> cards;
    private final Map<String, Object> exampleCache = new HashMap<>();

    private CultureCardRepository(List<CultureCard> cards) {
        this.cards = cards;
    }

    public List<CultureCard> allCards() {
        return cards;
    }

    /**
     * Load and parse all cards under assets/formacraft/culture_cards.
     * Throws if the mod container or path is missing.
     */
    public static CultureCardRepository load() throws Exception {
        Path dir = findAssetsDir("culture_cards");
        if (dir == null) {
            throw new IllegalStateException("culture_cards not found (neither in Fabric resources nor in src/main/resources)");
        }

        List<Path> files = new ArrayList<>();
        try (var s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
             .sorted(Comparator.comparing(p -> p.getFileName().toString()))
             .forEach(files::add);
        }

        List<CultureCard> cards = new ArrayList<>();
        for (Path p : files) {
            Object json;
            try (var in = Files.newInputStream(p)) {
                json = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), Object.class);
            }
            CultureCard cc = parseCard(json);
            if (cc != null) cards.add(cc);
        }
        return new CultureCardRepository(Collections.unmodifiableList(cards));
    }

    /**
     * Load a referenced assembly example (assets/formacraft/assembly_examples/<name>.json) as generic JSON object.
     * Returns null if not found or invalid.
     */
    public Object loadExample(String exampleFileName) {
        if (exampleFileName == null || exampleFileName.isBlank()) return null;
        String key = exampleFileName.trim();
        if (exampleCache.containsKey(key)) return exampleCache.get(key);
        try {
            Path examplesDir = findAssetsDir("assembly_examples");
            Path p = null;
            if (examplesDir != null) {
                Path cand = examplesDir.resolve(key);
                if (Files.exists(cand)) p = cand;
            }
            if (p == null) {
                // best-effort: try Fabric path directly if available
                ModContainer mod = FabricLoader.getInstance().getModContainer(MOD_ID).orElse(null);
                if (mod != null) p = mod.findPath("assets/" + MOD_ID + "/assembly_examples/" + key).orElse(null);
            }
            if (p == null) {
                exampleCache.put(key, null);
                return null;
            }
            Object json;
            try (var in = Files.newInputStream(p)) {
                json = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), Object.class);
            }
            exampleCache.put(key, json);
            return json;
        } catch (Exception e) {
            LOG.debug("load assembly example failed file={}", key, e);
            exampleCache.put(key, null);
            return null;
        }
    }

    /**
     * Find a dir under assets/formacraft/<subdir>.
     * Prefers Fabric mod container resources; falls back to project filesystem for build tools.
     */
    private static Path findAssetsDir(String subdir) {
        // 1) Fabric runtime path (in game / in dev run)
        try {
            ModContainer mod = FabricLoader.getInstance().getModContainer(MOD_ID).orElse(null);
            if (mod != null) {
                Path p = mod.findPath("assets/" + MOD_ID + "/" + subdir).orElse(null);
                if (p != null && Files.exists(p) && Files.isDirectory(p)) return p;
            }
        } catch (Throwable ex) { LOG.debug("find assets dir via Fabric failed subdir={}", subdir, ex); }

        // 2) Build-tool fallback (gradle JavaExec)
        try {
            Path p = Path.of("src/main/resources/assets/" + MOD_ID + "/" + subdir);
            if (Files.exists(p) && Files.isDirectory(p)) return p;
        } catch (Throwable ex) { LOG.debug("find assets dir via filesystem failed subdir={}", subdir, ex); }

        return null;
    }

    // ---------------- parsing helpers ----------------

    @SuppressWarnings("unchecked")
    private static CultureCard parseCard(Object json) {
        if (!(json instanceof Map<?, ?> mm)) return null;
        Map<String, Object> m;
        try { m = (Map<String, Object>) mm; } catch (Exception e) { return null; }

        String id = str(m.get("id"), null);
        String styleId = str(m.get("styleId"), null);
        if (id == null || styleId == null) return null;

        List<String> intents = strList(m.get("intents"));
        List<String> keywords = strList(m.get("keywords"));
        Map<String, List<String>> synonyms = strListMap(m.get("synonyms"));
        Map<String, Double> keywordWeights = doubleMap(m.get("keywordWeights"));
        List<String> negativeKeywords = strList(m.get("negativeKeywords"));
        List<String> exampleRefs = strList(m.get("exampleRefs"));

        List<CultureCard.CultureArchetype> archetypes = new ArrayList<>();
        Object archObj = m.get("archetypes");
        if (archObj instanceof List<?> list) {
            for (Object it : list) {
                if (!(it instanceof Map<?, ?> am0)) continue;
                Map<String, Object> am;
                try { am = (Map<String, Object>) am0; } catch (Exception e) { continue; }
                String name = str(am.get("name"), null);
                String exampleRef = str(am.get("exampleRef"), null);
                Map<String, Object> macroHint = null;
                Object mh = am.get("macroHint");
                if (mh instanceof Map<?, ?> mh0) {
                    macroHint = (Map<String, Object>) mh0;
                }
                List<String> constraints = strList(am.get("constraints"));
                if (name != null) {
                    archetypes.add(new CultureCard.CultureArchetype(name, exampleRef, macroHint, constraints));
                }
            }
        }

        return new CultureCard(id, styleId, intents, keywords, synonyms, keywordWeights, negativeKeywords, exampleRefs, archetypes);
    }

    private static String str(Object v, String def) {
        if (v == null) return def;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? def : s;
    }

    private static List<String> strList(Object v) {
        if (!(v instanceof List<?> list)) return List.of();
        ArrayList<String> out = new ArrayList<>();
        for (Object it : list) {
            if (!(it instanceof String s)) continue;
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return Collections.unmodifiableList(out);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> strListMap(Object v) {
        if (!(v instanceof Map<?, ?> mm)) return Map.of();
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (var e : mm.entrySet()) {
            String k = str(e.getKey(), null);
            if (k == null) continue;
            List<String> vs = strList(e.getValue());
            if (!vs.isEmpty()) out.put(k, vs);
        }
        return Collections.unmodifiableMap(out);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Double> doubleMap(Object v) {
        if (!(v instanceof Map<?, ?> mm)) return Map.of();
        Map<String, Double> out = new LinkedHashMap<>();
        for (var e : mm.entrySet()) {
            String k = str(e.getKey(), null);
            if (k == null) continue;
            Double d = doubleOrNull(e.getValue());
            if (d != null) out.put(k, d);
        }
        return Collections.unmodifiableMap(out);
    }

    private static Double doubleOrNull(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        try {
            return Double.parseDouble(s);
        } catch (Exception ex) {
            LOG.debug("parse double failed value={}", v, ex);
            return null;
        }
    }
}


