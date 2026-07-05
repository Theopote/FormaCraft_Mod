package com.formacraft.common.generation.component.util;

import java.util.Locale;

/**
 * A2: String-id facade styler for the Component main path ({@code MassMainGenerator}).
 *
 * <p>Mirrors the semantics of the server-side {@code HouseGeneratorUtils.applyWallPattern} /
 * {@code applyFacadeProfileToWallCell}, but operates on block-id strings (the {@code BlockPatch}
 * data representation) so the same facade rhythm can be applied on the LlmPlan/Component path
 * before world placement. All methods are pure and deterministic.</p>
 *
 * <p>Contract: when the pattern/profile is blank or the cell is not an exterior wall cell, the
 * input id is returned unchanged, so callers can route every wall cell through this styler without
 * any behavioral regression when no style is configured.</p>
 */
public final class ComponentFacadeStyler {

    private ComponentFacadeStyler() {}

    /**
     * Height-based wall pattern (gradient / striped / random). Returns {@code wallId} unchanged for
     * unknown/blank patterns.
     */
    public static String applyWallPattern(String wallId, String trimId, String foundationId,
                                          String pattern, int y, int height) {
        if (wallId == null) {
            return null;
        }
        String p = (pattern == null) ? "" : pattern.trim().toLowerCase(Locale.ROOT);
        switch (p) {
            case "gradient" -> {
                if (y <= 1) return foundationId != null ? foundationId : wallId;
                if (y >= height - 2) return trimId != null ? trimId : wallId;
                return wallId;
            }
            case "striped" -> {
                if (y % 3 == 0) return trimId != null ? trimId : wallId;
                return wallId;
            }
            case "random" -> {
                // Best-effort cracked/mossy variation for stone-brick walls (id-string based).
                if (wallId.endsWith("stone_bricks")) {
                    int r = (y * 31 + height * 17) & 7;
                    if (r == 0) return "minecraft:cracked_stone_bricks";
                    if (r == 1) return "minecraft:mossy_stone_bricks";
                }
                return wallId;
            }
            default -> {
                return wallId;
            }
        }
    }

    /**
     * Facade-profile composition on exterior wall cells (base_plinth / vertical_pilasters /
     * mullion_grid / module_grid). Only mutates cells flagged as exterior; otherwise returns
     * {@code current} untouched. Never clobbers a cell whose material already diverged from
     * {@code wallId} (e.g. a gradient/striped substitution applied earlier).
     */
    public static String applyFacadeProfile(String current, String wallId, String trimId, String foundationId,
                                            String facadeProfile, boolean isExterior, boolean isEdgeZ,
                                            int x, int y, int z, int width, int depth, int floorHeight) {
        return applyFacadeProfile(current, wallId, trimId, foundationId, facadeProfile, isExterior, isEdgeZ,
                x, y, z, width, depth, floorHeight, null, isEdgeZ ? x : z);
    }

    /**
     * @param rhythmPilasterAxes 由 {@link ComponentFacadeRhythmPlanner} 算出的柱位；非 null 且非空时覆盖 cadence 取模
     * @param facadeAxis         当前立面 cell 在开间方向上的坐标（x 或 z）
     */
    public static String applyFacadeProfile(String current, String wallId, String trimId, String foundationId,
                                            String facadeProfile, boolean isExterior, boolean isEdgeZ,
                                            int x, int y, int z, int width, int depth, int floorHeight,
                                            java.util.BitSet rhythmPilasterAxes, int facadeAxis) {
        if (current == null) {
            return null;
        }
        String fp = (facadeProfile == null) ? "" : facadeProfile.trim().toLowerCase(Locale.ROOT);
        if (fp.isEmpty() || !isExterior) {
            return current;
        }
        // Don't clobber non-wall materials (trim/foundation already substituted earlier).
        if (wallId != null && !current.equals(wallId)) {
            return current;
        }

        // base plinth: heavier base band
        if (fp.contains("base_plinth")) {
            if (y == 1) return foundationId != null ? foundationId : current;
            return current;
        }

        // vertical pilasters: rhythm-driven strips, else periodic cadence fallback
        if (fp.contains("vertical_pilasters") || fp.contains("pilasters")) {
            if (rhythmPilasterAxes != null && !rhythmPilasterAxes.isEmpty()) {
                if (y > 0 && facadeAxis >= 0 && rhythmPilasterAxes.get(facadeAxis)) {
                    return trimOr(trimId, current);
                }
                return current;
            }
            int cadence = 3;
            if (isEdgeZ) {
                if (x > 0 && x < width - 1 && (x % cadence == 0) && y > 0) return trimOr(trimId, current);
            } else {
                if (z > 0 && z < depth - 1 && (z % cadence == 0) && y > 0) return trimOr(trimId, current);
            }
            return current;
        }

        // mullion grid: floor bands + subtle vertical mullions
        if (fp.contains("mullion_grid") || fp.contains("mullion")) {
            int localY = (floorHeight > 0) ? (y % floorHeight) : 0;
            if (y > 0 && localY == 0) return trimOr(trimId, current);
            int cadence = 2;
            if (isEdgeZ) {
                if (x > 0 && x < width - 1 && (x % cadence == 0) && y > 0) return trimOr(trimId, current);
            } else {
                if (z > 0 && z < depth - 1 && (z % cadence == 0) && y > 0) return trimOr(trimId, current);
            }
            return current;
        }

        // module grid: brutalist-ish panelization
        if (fp.contains("module_grid")) {
            if (y > 0 && (y % 3 == 0)) return trimOr(trimId, current);
            if (isEdgeZ) {
                if (x > 0 && x < width - 1 && (x % 3 == 0) && y > 0) return trimOr(trimId, current);
            } else {
                if (z > 0 && z < depth - 1 && (z % 3 == 0) && y > 0) return trimOr(trimId, current);
            }
            return current;
        }

        return current;
    }

    private static String trimOr(String trimId, String current) {
        return (trimId != null && !trimId.isBlank()) ? trimId : current;
    }
}
