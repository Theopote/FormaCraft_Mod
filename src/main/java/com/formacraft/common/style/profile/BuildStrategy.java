package com.formacraft.common.style.profile;

/**
 * BuildStrategy: how to "build" a certain role/area in an expressive way (v1).
 * This is intentionally small and can be expanded as interpreters start consuming it.
 */
public enum BuildStrategy {
    SOLID_WALL,
    WINDOWED_WALL,
    OPEN_ARCADE,
    COLUMN_RING,
    TERRACE,
    ROOF_SLOPE,
    ROOF_FLAT
}


