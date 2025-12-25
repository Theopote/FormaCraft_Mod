package com.formacraft.server.terrain;

/**
 * ClusterTerrainPolicy (v1):
 * A "settlement-scale philosophy" for how much we disturb terrain.
 */
public enum ClusterTerrainPolicy {
    PRESERVE_DOMINANT, // terrain dominates; prefer minimal disturbance
    BALANCED,          // local shaping allowed
    ENGINEERED         // engineering dominates; allow higher disturbance (but still per-unit unless explicitly flatten)
}


