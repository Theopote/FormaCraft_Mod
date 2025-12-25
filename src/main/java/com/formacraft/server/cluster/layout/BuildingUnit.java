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
    /** Semantic role label (v1): CORE / PUBLIC / PRIVATE / SERVICE / TRANSITION / ... */
    public final String semanticRole;

    public BuildingUnit(String id, int width, int depth, int height, int importance) {
        this(id, width, depth, height, importance, "SEMI_PUBLIC");
    }

    public BuildingUnit(String id, int width, int depth, int height, int importance, String semanticRole) {
        this.id = id;
        this.width = Math.max(1, width);
        this.depth = Math.max(1, depth);
        this.height = Math.max(1, height);
        this.importance = importance;
        this.semanticRole = (semanticRole != null && !semanticRole.isBlank()) ? semanticRole : "SEMI_PUBLIC";
    }
}


