package com.formacraft.common.style.profile;

import com.formacraft.common.style.catalog.StyleProfileCatalog;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Adapter: StyleProfileCatalog entry -> StyleProfile (v1).
 *
 * v1 mapping policy:
 * - palette: picks the first item from defaults.materials.<slot> arrays, if present.
 * - rules/details: derived from defaults.geometry + defaults.components + constraints.*
 *
 * This intentionally preserves unknown fields (e.g. algorithm hints) in rawDefaults for future use.
 */
public final class CatalogStyleProfile implements StyleProfile {
    private final String id;
    private final StyleCategory category;
    private final BlockPalette palette;
    private final StyleRules rules;
    private final DetailPreferences details;

    private final StyleProfileCatalog.Meta meta;
    private final StyleProfileCatalog.Defaults defaults;
    private final StyleProfileCatalog.Constraints constraints;

    public CatalogStyleProfile(
            String id,
            StyleProfileCatalog.StyleProfileDef def,
            StyleCategory categoryFallback
    ) {
        this.id = (id == null || id.isBlank()) ? "default" : id.trim();
        this.meta = def != null ? def.meta : new StyleProfileCatalog.Meta();
        this.defaults = def != null ? def.defaults : new StyleProfileCatalog.Defaults();
        this.constraints = def != null ? def.constraints : new StyleProfileCatalog.Constraints();

        this.category = categoryFallback != null ? categoryFallback : StyleCategory.CULTURAL;
        this.palette = new BlockPalette();
        this.rules = new StyleRules();
        this.details = new DetailPreferences();

        // ---------- palette ----------
        Map<String, Object> mats = defaults != null ? defaults.materials : null;
        if (mats != null) {
            // Common keys from the proposed catalog: wall/roof/floor/window/foundation/trim/pillar/cap/frame/accent
            palette.wall = pickBlockId(mats.get("wall"), null);
            palette.roof = pickBlockId(mats.get("roof"), null);
            palette.floor = pickBlockId(mats.get("floor"), null);
            palette.window = pickBlockId(mats.get("window"), null);
            palette.foundation = pickBlockId(mats.get("foundation"), null);
            palette.trim = pickBlockId(mats.get("trim"), pickBlockId(mats.get("accent"), null));
            palette.pillar = pickBlockId(mats.get("pillar"), pickBlockId(mats.get("frame"), null));
            palette.cap = pickBlockId(mats.get("cap"), null);

            // Optional variants
            palette.wallVariants = pickListOfStrings(mats.get("wall"));
            palette.roofVariants = pickListOfStrings(mats.get("roof"));
        }

        // ---------- rules (geometry) ----------
        Map<String, Object> geom = defaults != null ? defaults.geometry : null;
        if (geom != null) {
            // symmetry: none / mirror / radial
            String sym = asString(geom.get("symmetry"));
            if (!sym.isBlank()) {
                String s = sym.toLowerCase(Locale.ROOT);
                rules.preferSymmetry = s.contains("mirror") || s.contains("radial");
            }

            // roof: { type: flat/gable/hip/hipped/pyramid/cone/spires... }
            Object roof = geom.get("roof");
            if (roof instanceof Map<?, ?> rm) {
                String rt = asString(rm.get("type")).toLowerCase(Locale.ROOT);
                if (!rt.isBlank()) {
                    rules.allowFlatRoof = rt.contains("flat");
                    // layered roof is a good default for hipped/pyramid-ish silhouettes
                    rules.layeredRoof = rt.contains("hip") || rt.contains("pyramid");
                    rules.roofTypeHint = normalizeRoofTypeHint(rt);
                }
            }
        }

        // ---------- components -> density/details ----------
        Map<String, Object> comps = defaults != null ? defaults.components : null;
        if (comps != null) {
            String win = asString(comps.get("windows")).toLowerCase(Locale.ROOT);
            if (!win.isBlank()) {
                // simple deterministic mapping for v1
                if (win.contains("small")) rules.windowDensity = 0.18f;
                else if (win.contains("rose")) rules.windowDensity = 0.65f;
                else if (win.contains("large")) rules.windowDensity = 0.75f;

                // windowStyle hint for generators (best-effort)
                if (details.windowStyle == null || details.windowStyle.isBlank()) {
                    if (win.contains("shoji") || win.contains("paper")) details.windowStyle = "shoji";
                    else if (win.contains("lattice") || win.contains("fence")) details.windowStyle = "fence";
                    else if (win.contains("curtain")) details.windowStyle = "curtain_wall";
                    else if (win.contains("slit")) details.windowStyle = "slit";
                    else if (win.contains("bars")) details.windowStyle = "bars";
                    else if (win.contains("stained") || win.contains("glass")) details.windowStyle = "stained";
                    else if (win.contains("none")) details.windowStyle = "slit";
                }
            }

            // Entry/portal style hint (cross-style)
            String portal = asString(comps.get("portal")).toLowerCase(Locale.ROOT);
            if (portal.isBlank()) portal = asString(comps.get("entry")).toLowerCase(Locale.ROOT);
            if (!portal.isBlank()) {
                details.portalStyle = portal;
                // Some portal types imply arches
                if (portal.contains("gothic") || portal.contains("pointed")) details.pointedArches = true;
                if (portal.contains("arch")) details.arches = true;
            }

            // Eaves / roof-edge profile hint (cross-style)
            String eaves = asString(comps.get("eaves")).toLowerCase(Locale.ROOT);
            if (eaves.isBlank()) eaves = asString(comps.get("roof_eaves")).toLowerCase(Locale.ROOT);
            if (!eaves.isBlank()) {
                details.eavesProfile = eaves;
                if (eaves.contains("flying")) details.emphasizeEaves = true;
            }

            // Facade composition hint (cross-style)
            String facade = asString(comps.get("facade_profile")).toLowerCase(Locale.ROOT);
            if (facade.isBlank()) facade = asString(comps.get("facade")).toLowerCase(Locale.ROOT);
            if (!facade.isBlank()) {
                details.facadeProfile = facade;
            }

            // Ornament/props hint (cross-style)
            String orn = asString(comps.get("ornament_profile")).toLowerCase(Locale.ROOT);
            if (orn.isBlank()) orn = asString(comps.get("ornament")).toLowerCase(Locale.ROOT);
            if (!orn.isBlank()) {
                details.ornamentProfile = orn;
            }

            // Palette hint for semantic material picks (PaletteResolver).
            String pid = asString(comps.get("palette_id")).trim();
            if (pid.isBlank()) pid = asString(comps.get("paletteId")).trim();
            if (pid.isBlank()) pid = asString(comps.get("palette")).trim();
            if (!pid.isBlank()) {
                details.paletteId = pid;
            }

            // Gothic: rose windows + buttresses
            if (win.contains("rose")) {
                details.roseWindow = true;
                details.buttresses = true;
                details.pointedArches = true;
                details.mullions = true;
                if (details.windowStyle == null || details.windowStyle.isBlank()) details.windowStyle = "stained";
            }

            // Classical: columns + pediment
            String cols = asString(comps.get("columns")).toLowerCase(Locale.ROOT);
            if (!cols.isBlank()) {
                details.colonnade = cols.contains("dense") || cols.contains("colonnade") || cols.contains("portico");
                if (details.colonnade) details.decorativeColumns = true;
                if (cols.contains("sparse")) details.colonnadeSpacing = 4;
                else if (cols.contains("medium")) details.colonnadeSpacing = 3;
                else details.colonnadeSpacing = 2; // dense/default
                if (cols.contains("peristyle")) details.peristyle = true;
            }

            String order = asString(comps.get("column_order")).toLowerCase(Locale.ROOT);
            if (!order.isBlank()) {
                if (order.contains("corinth")) details.classicalColumnOrder = "corinthian";
                else if (order.contains("ionic")) details.classicalColumnOrder = "ionic";
                else details.classicalColumnOrder = "doric";
            }

            Object per = comps.get("peristyle");
            if (per instanceof Boolean b) details.peristyle = b;

            Object sty = comps.get("stylobate");
            if (sty instanceof Boolean b) details.stylobate = b;
            // Peristyle typically reads better with a podium ring
            if (details.peristyle && !details.stylobate) details.stylobate = true;
            Object ped = comps.get("pediment");
            if (ped instanceof Boolean b) {
                details.pediment = b;
            } else if (ped != null) {
                String s = String.valueOf(ped).trim().toLowerCase(Locale.ROOT);
                if (!s.isBlank()) details.pediment = (s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on"));
            }
            if (details.colonnade || details.pediment) {
                details.entablature = true;
            }

            // Explicit buttresses override
            Object butt = comps.get("buttresses");
            if (butt instanceof Boolean b) details.buttresses = b;

            // Explicit mullions override
            Object mul = comps.get("mullions");
            if (mul instanceof Boolean b) details.mullions = b;

            // Explicit pointed arch windows override
            Object paw = comps.get("pointed_arch_windows");
            if (paw instanceof Boolean b) details.pointedArches = b;
        }

        // ---------- constraints -> extra behavioral knobs ----------
        if (constraints != null) {
            String symPref = asString(constraints.symmetry_preference).toLowerCase(Locale.ROOT);
            if (!symPref.isBlank()) {
                rules.preferSymmetry = symPref.contains("mirror") || symPref.contains("radial");
            }
            String silhouette = asString(constraints.silhouette_strength).toLowerCase(Locale.ROOT);
            if (silhouette.contains("high")) {
                // broad hint: push recognizable details
                details.decorativeColumns = true;
            }
        }

        // If the catalog hints spires roof, it's very likely a Gothic silhouette; enable pointed arches.
        if (rules.roofTypeHint != null && rules.roofTypeHint.equalsIgnoreCase("spires")) {
            details.pointedArches = true;
        }

        // ---------- banner defaults ----------
        if (defaults != null && defaults.banner != null) {
            details.bannerEnabled = defaults.banner.enabled;
            String c = asString(defaults.banner.color).toLowerCase(Locale.ROOT);
            details.bannerColor = c.isBlank() ? null : c;
        }
    }

    public StyleProfileCatalog.Meta meta() {
        return meta;
    }

    public StyleProfileCatalog.Defaults defaults() {
        return defaults;
    }

    public StyleProfileCatalog.Constraints constraints() {
        return constraints;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public StyleCategory category() {
        return category;
    }

    @Override
    public BlockPalette palette() {
        return palette;
    }

    @Override
    public StyleRules rules() {
        return rules;
    }

    @Override
    public DetailPreferences details() {
        return details;
    }

    @Override
    public BuildStrategy resolve(String role, Set<String> semanticTags) {
        String r = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        boolean gate = semanticTags != null && semanticTags.contains("gate");

        if (gate) {
            // express gates if we have any "high silhouette" hints
            if (details.arches || details.decorativeColumns || details.emphasizeEaves) {
                return BuildStrategy.OPEN_ARCADE;
            }
            return BuildStrategy.SOLID_WALL;
        }
        if (r.contains("ROOF")) return rules.allowFlatRoof ? BuildStrategy.ROOF_FLAT : BuildStrategy.ROOF_SLOPE;
        if (r.contains("WALL")) return rules.windowDensity >= 0.45f ? BuildStrategy.WINDOWED_WALL : BuildStrategy.SOLID_WALL;
        return BuildStrategy.SOLID_WALL;
    }

    private static String asString(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static String normalizeMcId(String v) {
        String s = (v == null ? "" : v.trim());
        if (s.isBlank()) return null;
        // allow users to omit namespace
        if (!s.contains(":")) return "minecraft:" + s;
        return s;
    }

    private static String normalizeRoofTypeHint(String rt) {
        if (rt == null) return null;
        String s = rt.trim().toLowerCase(Locale.ROOT);
        if (s.isBlank()) return null;
        if (s.contains("spires") || s.contains("spire")) return "spires";
        if (s.contains("hip")) return "hipped";
        if (s.contains("pyramid")) return "pyramid";
        if (s.contains("cone")) return "cone";
        if (s.contains("gable")) return "gable";
        if (s.contains("flat")) return "flat";
        return s;
    }

    private static String pickBlockId(Object v, String fallback) {
        switch (v) {
            case null -> {
                return fallback;
            }
            case String s -> {
                String id = normalizeMcId(s);
                return id != null ? id : fallback;
            }
            case List<?> list when !list.isEmpty() -> {
                Object first = list.getFirst();
                if (first instanceof String s) {
                    String id = normalizeMcId(s);
                    return id != null ? id : fallback;
                }
            }
            default -> {
            }
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private static List<String> pickListOfStrings(Object v) {
        if (v instanceof List<?> list) {
            boolean ok = true;
            for (Object o : list) {
                if (!(o instanceof String)) {
                    ok = false;
                    break;
                }
            }
            if (ok) return (List<String>) list;
        }
        return null;
    }
}


