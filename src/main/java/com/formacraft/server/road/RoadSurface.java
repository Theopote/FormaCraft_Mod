package com.formacraft.server.road;

/**
 * RoadSurface: simple per-node surface classification for road decoration/costing.
 */
public enum RoadSurface {
    GROUND,
    STEP,      // dy = +/-1 between adjacent nodes
    BRIDGE,    // water/gap under the road
    BLOCKED
}


