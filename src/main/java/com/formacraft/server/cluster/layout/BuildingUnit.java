package com.formacraft.server.cluster.layout;

/**
 * BuildingUnit: a repeated building template for cluster layout.
 */
public final class BuildingUnit {
    public final String id;
    public final int width;
    public final int depth;
    public final int height;
    public final int importance; // higher => placed first

    public BuildingUnit(String id, int width, int depth, int height, int importance) {
        this.id = id;
        this.width = Math.max(1, width);
        this.depth = Math.max(1, depth);
        this.height = Math.max(1, height);
        this.importance = importance;
    }
}


