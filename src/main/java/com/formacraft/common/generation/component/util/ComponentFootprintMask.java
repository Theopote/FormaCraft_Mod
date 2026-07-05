package com.formacraft.common.generation.component.util;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.llm.dto.Component;

import java.util.Locale;
import java.util.Map;

/**
 * 与 {@code MassMainGenerator} 一致的平面 footprint 判定，供屋顶等附属构件复用。
 */
public final class ComponentFootprintMask {

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

    private record PatternConfig(PlanPattern pattern, int size, double ratio, String corner) {}

    private final int width;
    private final int depth;
    private final FootprintShape shape;
    private final int cornerRadius;
    private final PatternConfig pattern;

    private ComponentFootprintMask(int width, int depth, FootprintShape shape, int cornerRadius, PatternConfig pattern) {
        this.width = width;
        this.depth = depth;
        this.shape = shape;
        this.cornerRadius = cornerRadius;
        this.pattern = pattern;
    }

    public static ComponentFootprintMask from(SemanticComponent semantic, Map<String, Object> params, int width, int depth) {
        FootprintShape shape = resolveShape(params, semantic);
        int cornerRadius = resolveCornerRadius(params, width, depth, shape);
        PatternConfig pattern = resolvePlanPattern(params, semantic, width, depth, shape);
        return new ComponentFootprintMask(width, depth, shape, cornerRadius, pattern);
    }

    public boolean contains(int x, int z) {
        return isInsideFootprint(x, z, width, depth, shape, cornerRadius, pattern);
    }

    /**
     * 屋顶是否应落在此 mass 局部坐标（含合法飞檐外挑）。
     */
    public boolean shouldPlaceRoof(int localX, int localZ, int overhang) {
        if (contains(localX, localZ)) {
            return true;
        }
        if (overhang <= 0) {
            return false;
        }
        for (int ring = 1; ring <= overhang; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    int fx = localX - dx;
                    int fz = localZ - dz;
                    if (!contains(fx, fz) || !isExteriorCell(fx, fz)) {
                        continue;
                    }
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isExteriorCell(int x, int z) {
        if (!contains(x, z)) {
            return false;
        }
        return !contains(x + 1, z) || !contains(x - 1, z) || !contains(x, z + 1) || !contains(x, z - 1);
    }

    private static FootprintShape resolveShape(Map<String, Object> params, SemanticComponent semantic) {
        String shape = getParamString(params, "shape", "footprint_shape", "footprintShape");
        if (shape == null && semantic != null && semantic.genome() != null
                && semantic.genome().topology != null && semantic.genome().topology.layout != null) {
            String layout = semantic.genome().topology.layout;
            if (layout.equalsIgnoreCase("circular") || layout.equalsIgnoreCase("radial")) {
                shape = "circle";
            } else if (layout.equalsIgnoreCase("freeform")) {
                shape = "rounded_rect";
            }
        }
        if (shape == null) {
            return FootprintShape.RECTANGLE;
        }
        return switch (shape.trim().toLowerCase(Locale.ROOT)) {
            case "circle", "circular", "round" -> FootprintShape.CIRCLE;
            case "rounded_rect", "rounded", "roundrect", "round_rect" -> FootprintShape.ROUNDED_RECT;
            default -> FootprintShape.RECTANGLE;
        };
    }

    private static int resolveCornerRadius(Map<String, Object> params, int width, int depth, FootprintShape shape) {
        int radius = ComponentParamParsers.intParam(params, 0, "corner_radius", "cornerRadius");
        if (radius <= 0 && shape == FootprintShape.ROUNDED_RECT) {
            radius = Math.max(1, Math.min(width, depth) / 6);
        }
        int max = Math.max(1, Math.min(width, depth) / 2);
        return Math.max(0, Math.min(radius, max));
    }

    private static PatternConfig resolvePlanPattern(Map<String, Object> params, SemanticComponent semantic,
                                                    int width, int depth, FootprintShape shape) {
        String planType = getParamString(params, "plan_type", "planType",
                "footprint_pattern", "footprintPattern", "plan_pattern", "planPattern");
        Component c = semantic != null ? semantic.source() : null;

        if (planType != null) {
            String normalized = planType.trim().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty() || normalized.equals("none") || normalized.equals("default")) {
                planType = null;
            }
        }

        if (planType == null && c != null && c.features() != null) {
            for (String feature : c.features()) {
                if (feature == null) {
                    continue;
                }
                String lower = feature.toLowerCase(Locale.ROOT);
                if (lower.contains("courtyard") || lower.contains("siheyuan") || lower.contains("四合院")) {
                    planType = "courtyard";
                    break;
                }
                if (lower.contains("l-shape") || lower.contains("l_shape") || lower.contains("lshape")) {
                    planType = "l_shape";
                    break;
                }
                if (lower.contains("chinese") || lower.contains("hui") || lower.contains("中式")) {
                    planType = "cut_corners";
                    break;
                }
            }
        }

        if (planType == null && semantic != null && semantic.styleProfile() != null) {
            String upper = semantic.styleProfile().toUpperCase(Locale.ROOT);
            if (upper.contains("HUI") || upper.contains("CHINESE")) {
                planType = "cut_corners";
            }
        }

        PlanPattern pattern = parsePlanPattern(planType);
        if (pattern == PlanPattern.NONE) {
            return new PatternConfig(PlanPattern.NONE, 0, 0.0, null);
        }
        if (shape == FootprintShape.CIRCLE && pattern != PlanPattern.COURTYARD) {
            return new PatternConfig(PlanPattern.NONE, 0, 0.0, null);
        }

        int size = 0;
        double ratio = 0.0;
        String corner = null;

        switch (pattern) {
            case CROSS -> {
                size = ComponentParamParsers.intParam(params, 0, "arm_width", "cross_arm", "cross_arm_width", "armWidth");
                if (size <= 0) {
                    size = Math.max(3, Math.min(width, depth) / 3);
                }
            }
            case CUT_CORNERS -> {
                size = ComponentParamParsers.intParam(params, 0, "corner_cut", "cornerCut", "cut_corner", "cutCorner", "cut_size");
                if (size <= 0) {
                    size = Math.max(2, Math.min(width, depth) / 5);
                }
            }
            case L_SHAPE -> {
                size = ComponentParamParsers.intParam(params, 0, "l_cut", "lCut", "cut_size", "corner_cut", "cornerCut", "arm_width");
                if (size <= 0) {
                    size = Math.max(3, Math.min(width, depth) / 3);
                }
                corner = normalizeCorner(getParamString(params, "l_corner", "lCorner", "cut_corner", "cutCorner", "corner"));
            }
            case COURTYARD -> {
                Double ratioValue = ComponentParamParsers.doubleOrNull(params, "courtyard_ratio", "courtyardRatio",
                        "court_ratio", "void_ratio", "voidRatio");
                ratio = ratioValue != null && ratioValue > 0.0 ? ratioValue : 0.35;
            }
            default -> {
            }
        }

        if (pattern == PlanPattern.COURTYARD) {
            ratio = Math.max(0.2, Math.min(0.6, ratio));
        }
        size = Math.max(1, Math.min(size, Math.min(width, depth) - 2));

        return new PatternConfig(pattern, size, ratio, corner);
    }

    private static boolean isInsideFootprint(int x, int z, int width, int depth,
                                             FootprintShape shape, int cornerRadius,
                                             PatternConfig pattern) {
        if (!isInsideFootprintBase(x, z, width, depth, shape, cornerRadius)) {
            return false;
        }
        if (pattern == null || pattern.pattern == null || pattern.pattern == PlanPattern.NONE) {
            return true;
        }
        return switch (pattern.pattern) {
            case CROSS -> isInCrossArms(x, z, width, depth, pattern.size);
            case CUT_CORNERS -> !isInCornerCut(x, z, width, depth, pattern.size);
            case L_SHAPE -> !isInLCut(x, z, width, depth, pattern.size, pattern.corner);
            case COURTYARD -> !isInCourtyardHole(x, z, width, depth, pattern);
            default -> true;
        };
    }

    private static boolean isInsideFootprintBase(int x, int z, int width, int depth, FootprintShape shape, int cornerRadius) {
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

    private static boolean isInCrossArms(int x, int z, int width, int depth, int size) {
        int arm = Math.max(1, Math.min(size, Math.min(width, depth)));
        if (arm >= width || arm >= depth) {
            return true;
        }
        int startX = (width - arm) / 2;
        int endX = startX + arm - 1;
        int startZ = (depth - arm) / 2;
        int endZ = startZ + arm - 1;
        return (x >= startX && x <= endX) || (z >= startZ && z <= endZ);
    }

    private static boolean isInCornerCut(int x, int z, int width, int depth, int size) {
        if (size <= 0) {
            return false;
        }
        int cut = Math.max(1, Math.min(size, Math.min(width, depth) - 1));
        boolean left = x < cut;
        boolean right = x >= width - cut;
        boolean top = z < cut;
        boolean bottom = z >= depth - cut;
        return (left && top) || (left && bottom) || (right && top) || (right && bottom);
    }

    private static boolean isInLCut(int x, int z, int width, int depth, int size, String corner) {
        if (size <= 0) {
            return false;
        }
        int cut = Math.max(1, Math.min(size, Math.min(width, depth) - 1));
        String c = corner != null ? corner : "NE";
        return switch (c) {
            case "NW" -> x < cut && z < cut;
            case "SW" -> x < cut && z >= depth - cut;
            case "SE" -> x >= width - cut && z >= depth - cut;
            default -> x >= width - cut && z < cut;
        };
    }

    private static boolean isInCourtyardHole(int x, int z, int width, int depth, PatternConfig pattern) {
        if (pattern == null || pattern.pattern != PlanPattern.COURTYARD) {
            return false;
        }
        if (width < 3 || depth < 3) {
            return false;
        }
        double ratio = pattern.ratio <= 0.0 ? 0.35 : pattern.ratio;
        int holeWidth = Math.max(1, Math.min(width - 2, (int) Math.round(width * ratio)));
        int holeDepth = Math.max(1, Math.min(depth - 2, (int) Math.round(depth * ratio)));
        int startX = (width - holeWidth) / 2;
        int startZ = (depth - holeDepth) / 2;
        int endX = startX + holeWidth - 1;
        int endZ = startZ + holeDepth - 1;
        return x >= startX && x <= endX && z >= startZ && z <= endZ;
    }

    private static PlanPattern parsePlanPattern(String value) {
        if (value == null) {
            return PlanPattern.NONE;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) {
            return PlanPattern.NONE;
        }
        return switch (v) {
            case "cross", "cruciform", "plus", "t_shape", "t-shape", "tshape" -> PlanPattern.CROSS;
            case "cut_corners", "cut-corners", "cutcorner", "cut_corner", "chamfer", "chamfered", "octagon", "octagonal" ->
                    PlanPattern.CUT_CORNERS;
            case "l", "l_shape", "l-shape", "lshape", "corner", "corner_wing" -> PlanPattern.L_SHAPE;
            case "courtyard", "court", "ring", "donut", "siheyuan", "compound" -> PlanPattern.COURTYARD;
            default -> PlanPattern.NONE;
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
