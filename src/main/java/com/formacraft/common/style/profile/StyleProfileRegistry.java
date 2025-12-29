package com.formacraft.common.style.profile;

import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.style.catalog.StyleProfileCatalogRegistry;
import com.formacraft.common.style.StyleGenome;
import com.formacraft.common.style.StyleGenomeRegistry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StyleProfileRegistry (v1):
 * - Backed by existing StyleGenome JSON assets.
 * - Provides a stable runtime API for generators/interpreters.
 */
public final class StyleProfileRegistry {
    private StyleProfileRegistry() {}

    private static final Map<String, StyleProfile> CACHE = new ConcurrentHashMap<>();

    public static StyleProfile forStyle(BuildingStyle style) {
        StyleGenome g = StyleGenomeRegistry.forStyle(style);
        String key = (g != null && g.id != null && !g.id.isBlank()) ? g.id : "default";
        return CACHE.computeIfAbsent(key, k -> new GenomeStyleProfile(g, style));
    }

    public static StyleProfile load(String id, BuildingStyle fallbackStyle) {
        String sid = (id == null || id.isBlank()) ? "default" : id.trim();

        // Prefer new StyleProfileCatalog if a profile id exists there.
        var def = StyleProfileCatalogRegistry.get(sid);
        if (def != null) {
            return CACHE.computeIfAbsent(sid, k -> new CatalogStyleProfile(sid, def, StyleCategory.CULTURAL));
        }

        // Fallback: legacy StyleGenome assets.
        StyleGenome g = StyleGenomeRegistry.load(sid);
        String key = (g != null && g.id != null && !g.id.isBlank()) ? g.id : sid;
        return CACHE.computeIfAbsent(key, k -> new GenomeStyleProfile(g, fallbackStyle));
    }

    /**
     * Resolve by spec.extra.styleProfileId when present; otherwise fallback to BuildingStyle defaults.
     * Also applies some catalog constraints as spec.extra defaults (best-effort, non-clobbering).
     */
    public static StyleProfile resolve(com.formacraft.common.model.build.BuildingSpec spec) {
        BuildingStyle style = (spec != null && spec.getStyle() != null) ? spec.getStyle() : BuildingStyle.DEFAULT;
        Map<String, Object> extra = spec != null ? spec.getExtra() : null;
        String styleProfileId = resolveStyleProfileId(extra);
        StyleProfile profile = (styleProfileId != null) ? load(styleProfileId, style) : forStyle(style);

        // Apply catalog knobs into spec.extra (only when missing)
        if (spec != null && profile instanceof CatalogStyleProfile cp) {
            ensureExtraMap(spec);
            applyCatalogConstraintDefaults(spec.getExtra(), cp);
            applyCatalogDefaultAlgorithmHints(spec.getExtra(), cp);
        }
        return profile;
    }

    public static StyleProfile resolveByExtra(Map<String, Object> extra, BuildingStyle fallbackStyle) {
        BuildingStyle style = fallbackStyle != null ? fallbackStyle : BuildingStyle.DEFAULT;
        String styleProfileId = resolveStyleProfileId(extra);
        return (styleProfileId != null) ? load(styleProfileId, style) : forStyle(style);
    }

    private static String resolveStyleProfileId(Map<String, Object> extra) {
        if (extra == null) return null;
        Object v = extra.get("styleProfileId");
        if (v == null) v = extra.get("style_profile_id"); // tolerate snake_case
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isBlank() ? null : s;
    }

    private static void ensureExtraMap(com.formacraft.common.model.build.BuildingSpec spec) {
        if (spec.getExtra() != null) return;
        // Avoid pulling in HashMap in public API; simplest: use Map.of() is immutable, so use java.util.HashMap.
        spec.setExtra(new java.util.HashMap<>());
    }

    private static void applyCatalogConstraintDefaults(Map<String, Object> extra, CatalogStyleProfile profile) {
        if (extra == null || profile == null) return;
        var c = profile.constraints();
        if (c == null) return;

        // terrain_policy: follow | local_level | global_flatten -> TerrainPolicyResolver keys
        if (!extra.containsKey("terrainPolicy")) {
            String tp = c.terrain_policy == null ? "" : c.terrain_policy.trim().toLowerCase(java.util.Locale.ROOT);
            if (tp.contains("global_flatten")) extra.put("terrainPolicy", "FLATTEN_AREA");
            else if (tp.contains("follow")) extra.put("terrainPolicy", "FOLLOW");
            else if (tp.contains("local")) extra.put("terrainPolicy", "ADAPTIVE");
        }

        // symmetry_preference is already reflected in StyleRules, but expose as extra hint for future skeleton/layout.
        if (!extra.containsKey("symmetryPreference") && c.symmetry_preference != null && !c.symmetry_preference.isBlank()) {
            extra.put("symmetryPreference", c.symmetry_preference);
        }

        // silhouette strength as a future quality hint
        if (!extra.containsKey("silhouetteStrength") && c.silhouette_strength != null && !c.silhouette_strength.isBlank()) {
            extra.put("silhouetteStrength", c.silhouette_strength);
        }

        // allowed archetypes (do not clobber if user already set)
        if (!extra.containsKey("allowedArchetypes") && c.allowed_archetypes != null && !c.allowed_archetypes.isEmpty()) {
            extra.put("allowedArchetypes", c.allowed_archetypes);
        }
    }

    /**
     * v1: allow StyleProfileCatalog.defaults.algorithm.generator to nudge routing when user/LLM didn't specify intent.
     * This keeps "style" and "archetype" cooperating: style can provide a stable default generator family.
     * <p>
     * IMPORTANT:
     * - Never override explicit extra.template / extra.landmark
     * - Only map to generators that exist in the current mod (otherwise just store as algorithmHint)
     */
    private static void applyCatalogDefaultAlgorithmHints(Map<String, Object> extra, CatalogStyleProfile profile) {
        if (extra == null || profile == null) return;
        var d = profile.defaults();
        if (d == null || d.algorithm == null || d.algorithm.isEmpty()) return;

        // Don't override explicit intent
        boolean hasTemplate = extra.get("template") != null && !String.valueOf(extra.get("template")).trim().isEmpty();
        boolean hasLandmark = extra.get("landmark") != null && !String.valueOf(extra.get("landmark")).trim().isEmpty();

        Object gen0 = d.algorithm.get("generator");
        String gen = gen0 == null ? "" : String.valueOf(gen0).trim().toLowerCase(java.util.Locale.ROOT);
        if (gen.isBlank()) return;

        // Always keep raw hint (future-facing)
        if (!extra.containsKey("algorithmHint")) {
            extra.put("algorithmHint", gen);
        }

        if (hasTemplate || hasLandmark) return;

        // Minimal mapping table (only to existing generators/templates)
        // - tulou -> landmark tulou
        // - castle -> template castle_compound
        // - courtyard_temple -> template mingqing_courtyard (closest existing)
        // - office/block_stack -> template office_block
        // - office_district -> template office_district
        // Other (cathedral/parametric/truss/organic/wooden_frame...) are stored as algorithmHint only (no routing yet).
        if (gen.contains("tulou")) {
            extra.put("landmark", "tulou");
            return;
        }
        if (gen.contains("castle")) {
            extra.put("template", "castle_compound");
            return;
        }
        if (gen.contains("courtyard")) {
            extra.put("template", "mingqing_courtyard");
            return;
        }
        if (gen.contains("office_district") || gen.contains("office_park")) {
            extra.put("template", "office_district");
            return;
        }
        if (gen.contains("office") || gen.contains("block_stack")) {
            extra.put("template", "office_block");
        }
    }
}


