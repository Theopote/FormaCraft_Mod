package com.formacraft.common.style.profile;

/**
 * DetailPreferences: recognizable details that affect "likeness" (v1).
 */
public final class DetailPreferences {
    public boolean emphasizeEaves = false;
    public boolean cornerTowers = false;
    public boolean decorativeColumns = false;
    public boolean arches = false;

    // --- Classical / Gothic detail toggles (v1 MVP) ---
    public boolean colonnade = false;     // Greco-Roman colonnade / portico
    public boolean pediment = false;      // Greco-Roman pediment triangle
    public boolean roseWindow = false;    // Gothic rose window feature
    public boolean buttresses = false;    // Gothic flying buttresses

    /** Optional style defaults for banners/flags (null = unspecified). */
    public Boolean bannerEnabled = null;
    public String bannerColor = null;
}


