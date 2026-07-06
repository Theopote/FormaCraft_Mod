package com.formacraft.common.palette;

import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.style.profile.StyleProfile;

/**
 * Style-aware default palette ids from {@link PaletteRegistry} when spec/profile omit paletteId.
 */
public final class PaletteDefaults {

    public static final String YELLOWISH_CREAM = "PALETTE_YELLOWISH_CREAM_A";
    public static final String STONE_FORTRESS = "PALETTE_STONE_FORTRESS_A";
    public static final String EAST_ASIAN_WOOD = "PALETTE_EAST_ASIAN_WOOD_A";
    public static final String PARAMETRIC_WHITE = "PALETTE_PARAMETRIC_WHITE_A";
    public static final String CYBER_NEON = "PALETTE_CYBER_NEON_A";

    private PaletteDefaults() {}

    /**
     * Resolve a catalog palette id for houses when none was explicitly provided.
     */
    public static String forStyle(BuildingStyle style, StyleProfile profile) {
        if (profile != null && profile.id() != null) {
            String byProfile = forProfileId(profile.id());
            if (byProfile != null) return byProfile;
        }
        if (style == null) style = BuildingStyle.DEFAULT;
        return switch (style) {
            case MEDIEVAL -> STONE_FORTRESS;
            case ASIAN -> EAST_ASIAN_WOOD;
            case MODERN -> PARAMETRIC_WHITE;
            case FUTURISTIC -> CYBER_NEON;
            case RUSTIC, DEFAULT -> YELLOWISH_CREAM;
        };
    }

    private static String forProfileId(String profileId) {
        if (profileId == null || profileId.isBlank()) return null;
        String id = profileId.trim().toLowerCase(java.util.Locale.ROOT);
        if (id.contains("gothic") || id.contains("cathedral")) return "PALETTE_GOTHIC_CATHEDRAL_A";
        if (id.contains("huizhou")) return "PALETTE_HUIZHOU_WHITE_BLACK_A";
        if (id.contains("jiangnan") || id.contains("watertown")) return "PALETTE_JIANGNAN_WATERTOWN_A";
        if (id.contains("imperial") || id.contains("palace")) return "PALETTE_CHINESE_IMPERIAL_A";
        if (id.contains("elven")) return "PALETTE_ELVEN_ORGANIC_A";
        if (id.contains("industrial") || id.contains("bridge")) return "PALETTE_INDUSTRIAL_STEEL_A";
        if (id.contains("classical") || id.contains("marble")) return "PALETTE_CLASSICAL_MARBLE_A";
        if (id.contains("steampunk")) return "PALETTE_STEAMPUNK_COPPER_A";
        if (id.contains("brutal")) return "PALETTE_BRUTALISM_CONCRETE_A";
        if (id.contains("cyber") || id.contains("neon")) return CYBER_NEON;
        return null;
    }
}
