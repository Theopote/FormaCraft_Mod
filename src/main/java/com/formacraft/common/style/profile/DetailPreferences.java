package com.formacraft.common.style.profile;

/**
 * DetailPreferences: recognizable details that affect "likeness" (v1).
 */
public final class DetailPreferences {
    public boolean emphasizeEaves = false;
    public boolean cornerTowers = false;
    public boolean decorativeColumns = false;
    public boolean arches = false;
    /** Window expression hint (best-effort): pane | fence | lattice | shoji | stained | bars | curtain_wall | slit */
    public String windowStyle = null;
    /** Entry/portal expression hint (best-effort): torii | paifang | gothic_pointed | stone_arch | modern_frame | neon_frame | steampunk_riveted | organic_arch */
    public String portalStyle = null;
    /** Eaves / roof-edge expression hint (best-effort): flying_eaves | parapet | battlement | neon_strip | organic_vines */
    public String eavesProfile = null;
    /** Facade composition hint (best-effort): base_plinth | vertical_pilasters | mullion_grid | module_grid */
    public String facadeProfile = null;
    /** Ornament/props hint (best-effort): chinese_plaque | castle_banners | steampunk_pipes | cyber_signage | organic_lanterns */
    public String ornamentProfile = null;

    // --- Classical / Gothic detail toggles (v1 MVP) ---
    public boolean colonnade = false;     // Greco-Roman colonnade / portico
    public boolean pediment = false;      // Greco-Roman pediment triangle
    public boolean roseWindow = false;    // Gothic rose window feature
    public boolean buttresses = false;    // Gothic flying buttresses
    public boolean pointedArches = false; // Gothic pointed arch portal tendency
    public boolean entablature = false;   // Classical entablature band over columns
    public int colonnadeSpacing = 2;      // 2=dense, 3=medium, 4=sparse
    public boolean mullions = false;      // Gothic window mullions / leaded glass vibe

    /** Classical column order hint: doric | ionic | corinthian (best-effort). */
    public String classicalColumnOrder = "doric";
    /** Classical peristyle (columns around all sides). */
    public boolean peristyle = false;
    /** Classical stylobate / podium ring + front steps. */
    public boolean stylobate = false;

    /** Optional style defaults for banners/flags (null = unspecified). */
    public Boolean bannerEnabled = null;
    public String bannerColor = null;

        /** Optional paletteId hint for PaletteResolver semantic picks (null = unspecified). */
        public String paletteId = null;
}


