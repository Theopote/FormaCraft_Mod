package com.formacraft.server.terrain;

/**
 * TerrainPolicy (v1):
 * Controls how generation adapts to terrain.
 *
 * FOLLOW:
 *  - minimal / no terrain modification
 *  - place units as-is (generators may still clear interior)
 *
 * ADAPTIVE (default):
 *  - per-unit local adaptation: snap to local average height + small pad/fill/clear
 *  - does NOT flatten the whole area
 *
 * FLATTEN_AREA:
 *  - flatten the whole area (district footprint) to a common height
 *
 * TERRAFORM:
 *  - strong terrain shaping (reserved; v1 behaves like FLATTEN_AREA)
 */
public enum TerrainPolicy {
    FOLLOW,
    ADAPTIVE,
    FLATTEN_AREA,
    TERRAFORM
}


