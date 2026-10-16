package com.formacraft.common.generation.component.util;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.llm.dto.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Single authority for mass plan footprint occupancy (shape, plan pattern, courtyard hole).
 * MassMain, roofs, and other satellites must consume this — not re-derive courtyard geometry.
 * Courtyard hole bounds are inclusive min / inclusive max.
 */
public record ResolvedFootprint(
        int width,
        int depth,
        FootprintShape shape,
        int cornerRadius,
        PlanPattern pattern,
        int patternSize,
        double courtyardRatio,
        String lCorner,
        int wallThickness,
        int holeMinX,
        int holeMinZ,
        int holeMaxX,
        int holeMaxZ
) {
    public enum FootprintShape {
        RECTANGLE,
        CIRCLE,
        ROUNDED_RECT
    }

    public enum PlanPattern {
        NONE,
        CROSS,
        CUT_CORNERS,
        L_SHAPE,
        COURTYARD
    }

    public static ResolvedFootprint resolve(SemanticComponent semantic, Map<String, Object> params, int width, int depth) {
        int w = Math.max(1, width);
        int d = Math.max(1, depth);
        FootprintShape shape = resolveShape(params, semantic);
        int cornerRadius = resolveCornerRadius(params, w, d, shape);
        int wallThickness = resolveWallThickness(params);
        PatternFields pattern = resolvePlanPattern(params, semantic, w, d, shape);

        int holeMinX = 0, holeMinZ = 0, holeMaxX = -1, holeMaxZ = -1;
        if (pattern.pattern == PlanPattern.COURTYARD && pattern.ratio > 0.0 && pattern.ratio < 1.0) {
            double ratio = pattern.ratio;
            int wt = Math.max(1, wallThickness);
            int maxHoleW = Math.max(1, w - 2 * wt);
            int maxHoleD = Math.max(1, d - 2 * wt);
            int holeW = Math.max(1, Math.min(maxHoleW, (int) Math.round(w * ratio)));
            int holeD = Math.max(1, Math.min(maxHoleD, (int) Math.round(d * ratio)));
            holeMinX = (w - holeW) / 2;
            holeMinZ = (d - holeD) / 2;
            holeMaxX = holeMinX + holeW - 1;
            holeMaxZ = holeMinZ + holeD - 1;
        }

        return new ResolvedFootprint(
                w, d, shape, cornerRadius,
                pattern.pattern, pattern.size, pattern.ratio, pattern.corner,
                wallThickness,
                holeMinX, holeMinZ, holeMaxX, holeMaxZ);
    }

    public static ResolvedFootprint resolve(Component component, int width, int depth) {
        Map<String, Object> params = component == null ? null : component.params();
        SemanticComponent semantic = component == null ? null
                : new SemanticComponent(component.componentType(), null, component, null, null, null);
        return resolve(semantic, params, width, depth);
    }

    public boolean contains(int x, int z) {
        if (!isInsideBase(x, z)) {
            return false;
        }
        return switch (pattern) {
            case NONE -> true;
            case CROSS -> isInCrossArms(x, z);
            case CUT_CORNERS -> !isInCornerCut(x, z);
            case L_SHAPE -> !isInLCut(x, z);
            case COURTYARD -> !isCourtyardHole(x, z);
        };
    }

    public boolean isCourtyardHole(int x, int z) {
        if (pattern != PlanPattern.COURTYARD || holeMaxX < holeMinX || holeMaxZ < holeMinZ) {
            return false;
        }
        return x >= holeMinX && x <= holeMaxX && z >= holeMinZ && z <= holeMaxZ;
    }

    private boolean isInsideBase(int x, int z) {
        if (x < 0 || z < 0 || x >= width || z >= depth) {
            return false;
        }
        if (shape == FootprintShape.RECTANGLE) {
            return true;
        }
        if (shape == FootprintShape.CIRCLE) {
            double cx = (width - 1) / 2.0;
            double cz = (depth - 1) / 2.0;
            double rx = Math.max(0.5, width / 2.0);
            double rz = Math.max(0.5, depth / 2.0);
            double dx = (x - cx) / rx;
            double dz = (z - cz) / rz;
            return (dx * dx + dz * dz) <= 1.0;
        }
        int r = Math.max(0, Math.min(cornerRadius, Math.min(width, depth) / 2));
        if (r == 0) {
            return true;
        }
        int maxX = width - 1;
        int maxZ = depth - 1;
        boolean inXBand = x >= r && x <= maxX - r;
        boolean inZBand = z >= r && z <= maxZ - r;
        if (inXBand || inZBand) {
            return true;
        }
        int cornerX = x < r ? r - 1 : maxX - r + 1;
        int cornerZ = z < r ? r - 1 : maxZ - r + 1;
        double dx = x - cornerX;
        double dz = z - cornerZ;
        double rr = r - 0.5;
        return (dx * dx + dz * dz) <= (rr * rr);
    }

    private boolean isInCrossArms(int x, int z) {
        int arm = Math.max(1, Math.min(patternSize, Math.min(width, depth)));
        if (arm >= width || arm >= depth) {
            return true;
        }
        int startX = (width - arm) / 2;
        int endX = startX + arm - 1;
        int startZ = (depth - arm) / 2;
        int endZ = startZ + arm - 1;
        return (x >= startX && x <= endX) || (z >= startZ && z <= endZ);
    }

    private boolean isInCornerCut(int x, int z) {
        if (patternSize <= 0) {
            return false;
        }
        int cut = Math.max(1, Math.min(patternSize, Math.min(width, depth) - 1));
        boolean left = x < cut;
        boolean right = x >= width - cut;
        boolean top = z < cut;
        boolean bottom = z >= depth - cut;
        return (left && top) || (left && bottom) || (right && top) || (right && bottom);
    }

    private boolean isInLCut(int x, int z) {
        if (patternSize <= 0) {
            return false;
        }
        int cut = Math.max(1, Math.min(patternSize, Math.min(width, depth) - 1));
        String c = lCorner != null ? lCorner : "NE";
        return switch (c) {
            case "NW" -> x < cut && z < cut;
            case "SW" -> x < cut && z >= depth - cut;
            case "SE" -> x >= width - cut && z >= depth - cut;
            default -> x >= width - cut && z < cut;
        };
    }

    private static int resolveWallThickness(Map<String, Object> params) {
        int override = ComponentParamParsers.intParam(params, -1, "wall_thickness", "wallThickness");
        return override > 0 ? override : 1;
    }

    private static FootprintShape resolveShape(Map<String, Object> params, SemanticComponent semantic) {
        Optional<String> explicit = FootprintShapeCatalog.extractExplicit(params);
        if (explicit.isPresent()) {
            return FootprintShapeCatalog.parseSupported(explicit.get())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "E_FOOTPRINT_SHAPE_UNSUPPORTED: Unsupported shape: " + explicit.get()));
        }
        // Technical default when shape was not authored. Genome/style must not invent topology here.
        return FootprintShape.RECTANGLE;
    }

    private static int resolveCornerRadius(Map<String, Object> params, int width, int depth, FootprintShape shape) {
        int radius = ComponentParamParsers.intParam(params, 0, "corner_radius", "cornerRadius");
        if (radius <= 0 && shape == FootprintShape.ROUNDED_RECT) {
            radius = Math.max(1, Math.min(width, depth) / 6);
        }
        int max = Math.max(1, Math.min(width, depth) / 2);
        return Math.max(0, Math.min(radius, max));
    }

    private record PatternFields(PlanPattern pattern, int size, double ratio, String corner) {}

    private static PatternFields resolvePlanPattern(Map<String, Object> params, SemanticComponent semantic,
                                                    int width, int depth, FootprintShape shape) {
        Optional<String> explicitPlan = PlanPatternCatalog.extractExplicit(params);
        PlanPattern pattern;
        if (explicitPlan.isPresent()) {
            pattern = PlanPatternCatalog.parseSupported(explicitPlan.get())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "E_PLAN_PATTERN_UNSUPPORTED: Unsupported plan_type: " + explicitPlan.get()));
        } else {
            // No style/feature/genome topology inference — geometry consumes authored params only.
            // Explicit rectangle shape without plan_type → solid rectangle.
            Optional<String> explicitShape = FootprintShapeCatalog.extractExplicit(params);
            if (explicitShape.isPresent()
                    && FootprintShapeCatalog.RECTANGLE_TOKENS.contains(
                    explicitShape.get().trim().toLowerCase(Locale.ROOT))) {
                pattern = PlanPattern.NONE;
            } else {
                pattern = PlanPattern.NONE;
            }
        }

        if (pattern == PlanPattern.NONE) {
            return new PatternFields(PlanPattern.NONE, 0, 0.0, null);
        }
        if (shape == FootprintShape.CIRCLE && PlanPatternCatalog.requiresNonCircularBase(pattern)) {
            throw new IllegalArgumentException(
                    "E_FOOTPRINT_COMBINATION_UNSUPPORTED: shape=circle is incompatible with plan_type="
                            + pattern.name().toLowerCase(Locale.ROOT));
        }

        int size = 0;
        double ratio = 0.0;
        String corner = null;
        int maxPatternSize = Math.min(width, depth) - 2;

        switch (pattern) {
            case CROSS -> {
                size = ComponentParamParsers.intParam(params, 0,
                        "arm_width", "cross_arm", "cross_arm_width", "armWidth", "crossArmWidth");
                if (size <= 0) {
                    size = Math.max(3, Math.min(width, depth) / 3);
                }
            }
            case CUT_CORNERS -> {
                size = ComponentParamParsers.intParam(params, 0,
                        "corner_cut", "cornerCut", "cut_corner", "cutCorner", "cut_size");
                if (size <= 0) {
                    size = Math.max(2, Math.min(width, depth) / 5);
                }
            }
            case L_SHAPE -> {
                size = ComponentParamParsers.intParam(params, 0,
                        "l_cut", "lCut", "cut_size", "corner_cut", "cornerCut", "arm_width");
                if (size <= 0) {
                    size = Math.max(3, Math.min(width, depth) / 3);
                }
                corner = normalizeCorner(getParamString(params, "l_corner", "lCorner", "cut_corner", "cutCorner", "corner"));
            }
            case COURTYARD -> {
                Double ratioValue = ComponentParamParsers.doubleOrNull(params,
                        "courtyard_ratio", "courtyardRatio", "court_ratio", "void_ratio", "voidRatio");
                if (ratioValue != null && ratioValue > 0.0 && ratioValue < 1.0) {
                    ratio = ratioValue;
                } else if (ratioValue == null) {
                    ratio = Math.max(0.2, Math.min(0.6, 0.35));
                } else {
                    // Illegal explicit ratio: leave 0; validator must reject before generate.
                    ratio = 0.0;
                }
            }
            default -> {
            }
        }

        // Explicit oversize is rejected by MassDesignValidator; clamp only heuristic sizes.
        Integer explicitSize = firstExplicitSize(params, pattern);
        if (explicitSize == null) {
            if (size > maxPatternSize && maxPatternSize > 0) {
                size = maxPatternSize;
            }
            size = Math.max(0, Math.min(size, Math.max(0, maxPatternSize)));
        } else {
            size = Math.max(0, size);
        }

        return new PatternFields(pattern, size, ratio, corner);
    }

    private static Integer firstExplicitSize(Map<String, Object> params, PlanPattern pattern) {
        return switch (pattern) {
            case CROSS -> ComponentParamParsers.intOrNull(params,
                    "arm_width", "cross_arm", "cross_arm_width", "armWidth", "crossArmWidth");
            case CUT_CORNERS -> ComponentParamParsers.intOrNull(params,
                    "corner_cut", "cornerCut", "cut_corner", "cutCorner", "cut_size");
            case L_SHAPE -> ComponentParamParsers.intOrNull(params,
                    "l_cut", "lCut", "cut_size", "corner_cut", "cornerCut", "arm_width");
            default -> null;
        };
    }

    private static String normalizeCorner(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) {
            return null;
        }
        if (v.contains("nw") || (v.contains("north") && v.contains("west"))) {
            return "NW";
        }
        if (v.contains("sw") || (v.contains("south") && v.contains("west"))) {
            return "SW";
        }
        if (v.contains("se") || (v.contains("south") && v.contains("east"))) {
            return "SE";
        }
        return "NE";
    }

    private static String getParamString(Map<String, Object> params, String... keys) {
        if (params == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key == null) {
                continue;
            }
            Object v = params.get(key);
            if (v == null) {
                continue;
            }
            String s = String.valueOf(v).trim();
            if (!s.isEmpty()) {
                return s;
            }
        }
        return null;
    }
}
