package com.formacraft.common.style.profile;

import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.style.StyleGenome;

import java.util.Locale;
import java.util.Set;

/**
 * Adapter: existing StyleGenome -> StyleProfile.
 * This lets us evolve to full StyleProfile without breaking existing JSON assets.
 */
public final class GenomeStyleProfile implements StyleProfile {
    private final String id;
    private final StyleCategory category;
    private final BlockPalette palette;
    private final StyleRules rules;
    private final DetailPreferences details;

    public GenomeStyleProfile(StyleGenome genome, BuildingStyle fallbackStyle) {
        String gid = (genome != null && genome.id != null && !genome.id.isBlank()) ? genome.id : "default";
        this.id = gid;

        // v1 heuristic category
        this.category = StyleCategory.CULTURAL;

        this.palette = new BlockPalette();
        if (genome != null && genome.palette != null) {
            this.palette.wall = genome.palette.wall;
            this.palette.roof = genome.palette.roof;
            this.palette.floor = genome.palette.floor;
            this.palette.window = genome.palette.window;
            this.palette.foundation = genome.palette.foundation;
            this.palette.trim = genome.palette.trim;
            this.palette.pillar = genome.palette.pillar;
            this.palette.cap = genome.palette.cap;
        }

        this.rules = new StyleRules();
        this.details = new DetailPreferences();

        // derive from style + genome params
        BuildingStyle s = fallbackStyle != null ? fallbackStyle : BuildingStyle.DEFAULT;
        String roofType = (genome != null && genome.params != null) ? genome.params.roofType : null;
        Double windowRatio = (genome != null && genome.params != null) ? genome.params.windowRatio : null;
        Double windowDensity = (genome != null && genome.params != null) ? genome.params.windowDensity : null;
        Integer floorHeight = (genome != null && genome.params != null) ? genome.params.floorHeight : null;
        Boolean preferSymmetry = (genome != null && genome.params != null) ? genome.params.preferSymmetry : null;
        Boolean layeredRoof = (genome != null && genome.params != null) ? genome.params.layeredRoof : null;
        Integer capLayers = (genome != null && genome.params != null) ? genome.params.capLayers : null;
        Integer capOverhang = (genome != null && genome.params != null) ? genome.params.capOverhang : null;

        if (roofType != null) {
            String rt = roofType.trim().toLowerCase(Locale.ROOT);
            this.rules.allowFlatRoof = rt.equals("flat");
            this.rules.layeredRoof = rt.equals("hipped") || rt.equals("pyramid");
            this.rules.roofTypeHint = rt;
        }
        if (windowDensity != null) {
            this.rules.windowDensity = (float) Math.max(0.0, Math.min(1.0, windowDensity));
        } else if (windowRatio != null) {
            // backward compatible: use ratio as density hint when density is not provided
            this.rules.windowDensity = (float) Math.max(0.0, Math.min(1.0, windowRatio));
        }
        if (floorHeight != null && floorHeight > 0) {
            this.rules.floorHeight = Math.max(2, Math.min(10, floorHeight));
        }
        if (preferSymmetry != null) {
            this.rules.preferSymmetry = preferSymmetry;
        }
        if (layeredRoof != null) {
            this.rules.layeredRoof = layeredRoof;
        }
        if (capLayers != null && capLayers > 0) {
            this.rules.capLayers = Math.max(1, Math.min(3, capLayers));
        }
        if (capOverhang != null) {
            this.rules.capOverhang = Math.max(0, Math.min(1, capOverhang));
        }

        switch (s) {
            case ASIAN -> {
                this.rules.preferSymmetry = true;
                this.details.emphasizeEaves = true;
            }
            case MEDIEVAL -> {
                this.rules.preferSymmetry = false;
                this.details.cornerTowers = true;
                this.details.arches = true;
            }
            case MODERN -> {
                this.rules.preferSymmetry = true;
                this.rules.allowFlatRoof = true;
                this.details.arches = false;
            }
            default -> {
                // keep defaults
            }
        }
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
            // Only some styles express gates as arcades/frames (e.g. ASIAN ceremonial gates, MEDIEVAL arches).
            if (details.arches || details.emphasizeEaves || details.decorativeColumns) {
                return BuildStrategy.OPEN_ARCADE;
            }
            return BuildStrategy.SOLID_WALL;
        }
        if (r.contains("ROOF")) return rules.allowFlatRoof ? BuildStrategy.ROOF_FLAT : BuildStrategy.ROOF_SLOPE;
        if (r.contains("WALL")) return rules.windowDensity >= 0.45f ? BuildStrategy.WINDOWED_WALL : BuildStrategy.SOLID_WALL;
        return BuildStrategy.SOLID_WALL;
    }
}


