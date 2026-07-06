package com.formacraft.common.typology;

import com.formacraft.common.json.JsonUtil;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Loads structural_typologies/structural_typologies_v1.json for typology-first routing.
 * Phase 0: legacyInterpreterId delegates to existing landmark generators via migrationMap.
 */
public final class StructuralTypologyRegistry {

    public record ReferenceLandmark(String archetypeId, String role, String notes) {}

    public record TypologyDef(
            String id,
            String displayNameZh,
            String displayNameEn,
            String skeletonType,
            List<String> styleFamilies,
            List<String> matchKeywords,
            List<String> negativeKeywords,
            String interpreterId,
            String legacyInterpreterId,
            String routingPolicy,
            Map<String, Object> defaultParams,
            Map<String, Object> paramSchema,
            List<ReferenceLandmark> referenceLandmarks,
            List<String> proportionCardIds,
            List<String> cultureCardIds,
            String llmPlanGuidance
    ) {}

    public record MigrationEntry(
            String typologyId,
            String phase,
            String legacyModuleId,
            String deprecatedAfter,
            String notes
    ) {}

    private static volatile List<TypologyDef> cachedTypologies;
    private static volatile Map<String, MigrationEntry> cachedMigration;

    private StructuralTypologyRegistry() {}

    public static List<TypologyDef> listTypologies() {
        ensureLoaded();
        return cachedTypologies;
    }

    public static TypologyDef getById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String key = id.trim();
        for (TypologyDef t : listTypologies()) {
            if (key.equals(t.id())) {
                return t;
            }
        }
        return null;
    }

    public static MigrationEntry getMigration(String legacyModuleId) {
        if (legacyModuleId == null || legacyModuleId.isBlank()) {
            return null;
        }
        ensureLoaded();
        return cachedMigration.get(legacyModuleId.trim());
    }

    /** Resolve typology id from a deprecated landmark module id. */
    public static String typologyForLegacyModule(String legacyModuleId) {
        MigrationEntry entry = getMigration(legacyModuleId);
        return entry != null ? entry.typologyId() : null;
    }

    /** Phase 0: map typology to legacy generator when no dedicated interpreter exists. */
    public static String resolveInterpreterId(String typologyId) {
        TypologyDef def = getById(typologyId);
        if (def == null) {
            return null;
        }
        if (def.interpreterId() != null && !def.interpreterId().isBlank()) {
            return def.interpreterId();
        }
        return def.legacyInterpreterId();
    }

    /** Legacy MODULE id to invoke when typology interpreter is not yet registered. */
    public static String legacyModuleForTypology(String typologyId) {
        TypologyDef def = getById(typologyId);
        return def != null ? def.legacyInterpreterId() : null;
    }

    public static TypologyDef resolveForPrompt(String userText) {
        if (userText == null || userText.isBlank()) {
            return null;
        }
        String lower = userText.toLowerCase(Locale.ROOT);
        TypologyDef best = null;
        double bestScore = 0;
        for (TypologyDef t : listTypologies()) {
            double score = 0;
            for (String kw : t.matchKeywords()) {
                if (kw != null && !kw.isBlank() && lower.contains(kw.toLowerCase(Locale.ROOT))) {
                    score += Math.max(2.0, kw.length() * 0.5);
                }
            }
            for (String nk : t.negativeKeywords()) {
                if (nk != null && !nk.isBlank() && lower.contains(nk.toLowerCase(Locale.ROOT))) {
                    score -= Math.max(2.0, nk.length() * 0.4);
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = t;
            }
        }
        return bestScore > 0.01 ? best : null;
    }

    public static String promptBlockForTypology(TypologyDef def) {
        if (def == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("""

            ========================================
            STRUCTURAL TYPOLOGY (parametric interpreter)
            ========================================
            """);
        sb.append("structural_typology: ").append(def.id())
          .append(" (").append(def.displayNameZh()).append(")\n");
        sb.append("skeleton_type: ").append(def.skeletonType()).append("\n");
        sb.append("routing_policy: ").append(def.routingPolicy()).append("\n");
        if (def.legacyInterpreterId() != null && !def.legacyInterpreterId().isBlank()) {
            sb.append("legacy_module_fallback: ").append(def.legacyInterpreterId())
              .append(" (Phase 0 only)\n");
        }
        if (def.llmPlanGuidance() != null && !def.llmPlanGuidance().isBlank()) {
            sb.append(def.llmPlanGuidance()).append("\n");
        }
        sb.append("Set proportion_hints.typology to ").append(def.id()).append(".\n\n");
        return sb.toString();
    }

    private static void ensureLoaded() {
        if (cachedTypologies != null) {
            return;
        }
        synchronized (StructuralTypologyRegistry.class) {
            if (cachedTypologies != null) {
                return;
            }
            loadFromJson();
        }
    }

    @SuppressWarnings("unchecked")
    private static void loadFromJson() {
        String path = "/assets/formacraft/structural_typologies/structural_typologies_v1.json";
        List<TypologyDef> typologies = new ArrayList<>();
        Map<String, MigrationEntry> migration = new LinkedHashMap<>();
        try (InputStream in = StructuralTypologyRegistry.class.getResourceAsStream(path)) {
            if (in == null) {
                cachedTypologies = List.of();
                cachedMigration = Map.of();
                return;
            }
            Map<String, Object> root = JsonUtil.get().fromJson(
                    new InputStreamReader(in, StandardCharsets.UTF_8), Map.class);
            List<Map<String, Object>> arr = (List<Map<String, Object>>) root.get("typologies");
            if (arr != null) {
                for (Map<String, Object> item : arr) {
                    TypologyDef def = parseTypology(item);
                    if (def != null) {
                        typologies.add(def);
                    }
                }
            }
            Map<String, Object> migRoot = (Map<String, Object>) root.get("migrationMap");
            if (migRoot != null) {
                for (Map.Entry<String, Object> e : migRoot.entrySet()) {
                    if (e.getValue() instanceof Map<?, ?> raw) {
                        Map<String, Object> entry = (Map<String, Object>) raw;
                        String typId = str(entry.get("typologyId"));
                        if (typId != null) {
                            migration.put(e.getKey(), new MigrationEntry(
                                    typId,
                                    str(entry.get("phase")),
                                    str(entry.get("legacyModuleId")),
                                    str(entry.get("deprecatedAfter")),
                                    str(entry.get("notes"))
                            ));
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            typologies = List.of();
            migration = Map.of();
        }
        cachedTypologies = Collections.unmodifiableList(typologies);
        cachedMigration = Collections.unmodifiableMap(migration);
    }

    @SuppressWarnings("unchecked")
    private static TypologyDef parseTypology(Map<String, Object> item) {
        if (item == null) {
            return null;
        }
        String id = str(item.get("id"));
        if (id == null) {
            return null;
        }
        Map<String, Object> dn = item.get("displayName") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        List<ReferenceLandmark> refs = new ArrayList<>();
        Object refArr = item.get("referenceLandmarks");
        if (refArr instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> raw) {
                    Map<String, Object> r = (Map<String, Object>) raw;
                    String aid = str(r.get("archetypeId"));
                    if (aid != null) {
                        refs.add(new ReferenceLandmark(
                                aid,
                                Optional.ofNullable(str(r.get("role"))).orElse("primary"),
                                Optional.ofNullable(str(r.get("notes"))).orElse("")
                        ));
                    }
                }
            }
        }
        return new TypologyDef(
                id,
                str(dn.get("zh")),
                str(dn.get("en")),
                str(item.get("skeletonType")),
                strList(item.get("styleFamilies")),
                strList(item.get("matchKeywords")),
                strList(item.get("negativeKeywords")),
                str(item.get("interpreterId")),
                str(item.get("legacyInterpreterId")),
                Optional.ofNullable(str(item.get("routingPolicy"))).orElse("typology_first"),
                objMap(item.get("defaultParams")),
                objMap(item.get("paramSchema")),
                List.copyOf(refs),
                strList(item.get("proportionCardIds")),
                strList(item.get("cultureCardIds")),
                str(item.get("llmPlanGuidance"))
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objMap(Object v) {
        if (v instanceof Map<?, ?> m) {
            return new LinkedHashMap<>((Map<String, Object>) m);
        }
        return Map.of();
    }

    private static List<String> strList(Object v) {
        if (!(v instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (o != null) {
                String s = o.toString().trim();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return List.copyOf(out);
    }

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
