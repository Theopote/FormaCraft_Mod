package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.ComponentParamParsers;
import com.formacraft.common.generation.component.util.FootprintShapeCatalog;
import com.formacraft.common.generation.component.util.PlanPatternCatalog;
import com.formacraft.common.generation.component.util.ResolvedFootprint;
import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Compile-time fidelity gates for MASS plan patterns, courtyard, and wall thickness. */
final class MassDesignValidator {
    private MassDesignValidator() {}

    private static final String[] COURTYARD_RATIO_KEYS = {
            "courtyard_ratio", "courtyardRatio", "court_ratio", "void_ratio", "voidRatio"
    };
    private static final String[] WALL_THICKNESS_KEYS = {
            "wall_thickness", "wallThickness"
    };

    static Optional<CapabilityGap> check(LlmPlan plan, List<Component> components) {
        if (components == null) {
            return Optional.empty();
        }
        for (Component component : components) {
            if (component == null || component.componentType() == null || component.dimensions() == null) {
                continue;
            }
            String type = component.componentType().toUpperCase(Locale.ROOT);
            if (!isMassType(type)) {
                continue;
            }
            if (component.params() != null && "plate".equalsIgnoreCase(String.valueOf(component.params().get("extrude_mode")))) continue;
            Optional<CapabilityGap> planUnsupported = checkPlanPatternUnsupported(component);
            if (planUnsupported.isPresent()) {
                return planUnsupported;
            }
            Optional<CapabilityGap> shapeUnsupported = checkFootprintShapeUnsupported(component);
            if (shapeUnsupported.isPresent()) {
                return shapeUnsupported;
            }
            Optional<CapabilityGap> combo = checkFootprintCombination(component);
            if (combo.isPresent()) {
                return combo;
            }
            Optional<CapabilityGap> wallInvalid = checkWallThicknessInvalid(component);
            if (wallInvalid.isPresent()) {
                return wallInvalid;
            }
            Optional<CapabilityGap> courtyardInvalid = checkCourtyardRatioInvalid(component);
            if (courtyardInvalid.isPresent()) {
                return courtyardInvalid;
            }
            Optional<CapabilityGap> wall = checkWallThickness(component);
            if (wall.isPresent()) {
                return wall;
            }
            Optional<CapabilityGap> courtyard = checkCourtyard(component);
            if (courtyard.isPresent()) {
                return courtyard;
            }
            Optional<CapabilityGap> pattern = checkPlanPatternSize(component);
            if (pattern.isPresent()) {
                return pattern;
            }
        }
        return Optional.empty();
    }

    private static boolean isMassType(String type) {
        return "MASS_MAIN".equals(type) || "MAIN_MASS".equals(type)
                || "MASS_SECONDARY".equals(type) || "MASS_WING".equals(type) || "SIDE_WING".equals(type);
    }

    private static Optional<CapabilityGap> checkPlanPatternUnsupported(Component component) {
        Map<String, Object> params = component.params() == null ? Map.of() : component.params();
        Optional<String> explicit = PlanPatternCatalog.extractExplicit(params);
        if (explicit.isEmpty()) {
            return Optional.empty();
        }
        if (!PlanPatternCatalog.isSupportedExplicit(explicit.get())) {
            return Optional.of(new CapabilityGap(
                    "E_PLAN_PATTERN_UNSUPPORTED",
                    "Unsupported plan_type: " + explicit.get(),
                    "components[]",
                    List.of("Use a supported plan_type (none/cross/cut_corners/l_shape/courtyard) or omit for a rectangle.")));
        }
        return Optional.empty();
    }

    private static Optional<CapabilityGap> checkFootprintShapeUnsupported(Component component) {
        Map<String, Object> params = component.params() == null ? Map.of() : component.params();
        Optional<String> explicit = FootprintShapeCatalog.extractExplicit(params);
        if (explicit.isEmpty()) {
            return Optional.empty();
        }
        if (!FootprintShapeCatalog.isSupportedExplicit(explicit.get())) {
            return Optional.of(new CapabilityGap(
                    "E_FOOTPRINT_SHAPE_UNSUPPORTED",
                    "Unsupported shape: " + explicit.get(),
                    "components[]",
                    List.of("Use a supported shape (rectangle/circle/rounded_rect) or omit for rectangle.")));
        }
        return Optional.empty();
    }

    /** Explicit circle + explicit non-courtyard plan pattern → unsupported combination. */
    private static Optional<CapabilityGap> checkFootprintCombination(Component component) {
        Map<String, Object> params = component.params() == null ? Map.of() : component.params();
        Optional<String> shapeRaw = FootprintShapeCatalog.extractExplicit(params);
        Optional<String> planRaw = PlanPatternCatalog.extractExplicit(params);
        if (shapeRaw.isEmpty() || planRaw.isEmpty()) {
            return Optional.empty();
        }
        if (!FootprintShapeCatalog.isSupportedExplicit(shapeRaw.get())
                || !PlanPatternCatalog.isSupportedExplicit(planRaw.get())) {
            return Optional.empty();
        }
        ResolvedFootprint.FootprintShape shape = FootprintShapeCatalog.parseSupported(shapeRaw.get()).orElse(null);
        ResolvedFootprint.PlanPattern pattern = PlanPatternCatalog.parseSupported(planRaw.get()).orElse(null);
        if (shape == ResolvedFootprint.FootprintShape.CIRCLE
                && pattern != null
                && PlanPatternCatalog.requiresNonCircularBase(pattern)) {
            return Optional.of(new CapabilityGap(
                    "E_FOOTPRINT_COMBINATION_UNSUPPORTED",
                    "shape=" + shapeRaw.get() + " is incompatible with plan_type=" + planRaw.get(),
                    "components[]",
                    List.of("Use plan_type=courtyard (or none) with circle, or change shape to rectangle.")));
        }
        return Optional.empty();
    }

    /** Explicit key present but non-numeric or <= 0 → INVALID (not UNFIT). */
    private static Optional<CapabilityGap> checkWallThicknessInvalid(Component component) {
        Map<String, Object> params = component.params() == null ? Map.of() : component.params();
        String presentKey = firstPresentKey(params, WALL_THICKNESS_KEYS);
        if (presentKey == null) {
            return Optional.empty();
        }
        Object raw = params.get(presentKey);
        Integer thickness = parseIntStrict(raw);
        if (thickness == null || thickness <= 0) {
            return Optional.of(new CapabilityGap(
                    "E_WALL_THICKNESS_INVALID",
                    "wall_thickness=" + raw + " is not a positive integer",
                    "components[]",
                    List.of("Provide wall_thickness as a positive integer, or omit for the technical default.")));
        }
        return Optional.empty();
    }

    /**
     * Explicit courtyard ratio key present but non-numeric / &lt;=0 / &gt;=1 → INVALID.
     * Only applies when courtyard plan is active or the ratio key itself was authored.
     */
    private static Optional<CapabilityGap> checkCourtyardRatioInvalid(Component component) {
        Map<String, Object> params = component.params() == null ? Map.of() : component.params();
        String presentKey = firstPresentKey(params, COURTYARD_RATIO_KEYS);
        if (presentKey == null) {
            return Optional.empty();
        }
        // void_ratio is shared with solid-mass semantics; skip INVALID when used as solid marker.
        if (("void_ratio".equals(presentKey) || "voidRatio".equals(presentKey))
                && !isCourtyardPlan(params, component.features())) {
            return Optional.empty();
        }
        Object raw = params.get(presentKey);
        Double ratio = parseDoubleStrict(raw);
        if (ratio == null || ratio <= 0.0 || ratio >= 1.0) {
            return Optional.of(new CapabilityGap(
                    "E_COURTYARD_RATIO_INVALID",
                    "courtyard_ratio=" + raw + " must be a number in (0, 1)",
                    "components[]",
                    List.of("Provide courtyard_ratio in (0, 1), or omit for the technical default.")));
        }
        return Optional.empty();
    }

    private static Optional<CapabilityGap> checkWallThickness(Component component) {
        Map<String, Object> params = component.params() == null ? Map.of() : component.params();
        Integer thickness = ComponentParamParsers.intOrNull(params, WALL_THICKNESS_KEYS);
        if (thickness == null || thickness <= 0) {
            return Optional.empty();
        }
        if (isSolidMass(params)) {
            return Optional.empty();
        }
        Dimensions d = component.dimensions();
        int minSpan = Math.min(d.width(), d.depth());
        if (2L * thickness >= minSpan) {
            return Optional.of(new CapabilityGap(
                    "E_WALL_THICKNESS_UNFIT",
                    "wall_thickness=" + thickness + " fills envelope minSpan=" + minSpan,
                    "components[]",
                    List.of("Reduce wall_thickness, enlarge the mass, or set void_ratio=0 for a solid mass.")));
        }
        return Optional.empty();
    }

    private static Optional<CapabilityGap> checkCourtyard(Component component) {
        Map<String, Object> params = component.params() == null ? Map.of() : component.params();
        if (!isCourtyardPlan(params, component.features())) {
            return Optional.empty();
        }
        Double ratio = ComponentParamParsers.doubleOrNull(params, COURTYARD_RATIO_KEYS);
        if (ratio == null || ratio <= 0 || ratio >= 1.0) {
            return Optional.empty();
        }
        Dimensions d = component.dimensions();
        int wallThickness = Math.max(1, ComponentParamParsers.intParam(params, 1, WALL_THICKNESS_KEYS));
        Optional<String> conflict = courtyardGeometryConflict(d.width(), d.depth(), ratio, wallThickness);
        if (conflict.isPresent()) {
            return Optional.of(new CapabilityGap(
                    "E_COURTYARD_GEOMETRY_CONFLICT",
                    conflict.get(),
                    "components[]",
                    List.of("Lower courtyard_ratio, reduce wall_thickness, or enlarge the footprint.")));
        }
        return Optional.empty();
    }

    private static Optional<CapabilityGap> checkPlanPatternSize(Component component) {
        Map<String, Object> params = component.params() == null ? Map.of() : component.params();
        Dimensions d = component.dimensions();
        int maxSize = Math.min(d.width(), d.depth()) - 2;
        if (maxSize < 1) {
            return Optional.empty();
        }
        Integer arm = ComponentParamParsers.intOrNull(params,
                "arm_width", "cross_arm", "cross_arm_width", "armWidth", "crossArmWidth");
        Integer lCut = ComponentParamParsers.intOrNull(params,
                "l_cut", "lCut", "cut_size", "corner_cut", "cornerCut");
        Integer cornerCut = ComponentParamParsers.intOrNull(params,
                "corner_cut", "cornerCut", "cut_corner", "cutCorner", "cut_size");
        Integer explicit = firstPositive(arm, lCut, cornerCut);
        if (explicit != null && explicit > maxSize) {
            return Optional.of(new CapabilityGap(
                    "E_PLAN_PATTERN_UNFIT",
                    "plan pattern size=" + explicit + " exceeds buildable max=" + maxSize
                            + " for footprint " + d.width() + "x" + d.depth(),
                    "components[]",
                    List.of("Reduce arm_width/l_cut/corner_cut or enlarge the footprint.")));
        }
        return Optional.empty();
    }

    /** Exposed for generator fail-closed checks. */
    static Optional<String> courtyardGeometryConflict(int width, int depth, double ratio, int wallThickness) {
        if (width < 3 || depth < 3 || ratio <= 0) {
            return Optional.of("courtyard requires footprint >= 3 and ratio > 0");
        }
        int wt = Math.max(1, wallThickness);
        int holeWidth = (int) Math.round(width * ratio);
        int holeDepth = (int) Math.round(depth * ratio);
        int maxHoleW = width - 2 * wt;
        int maxHoleD = depth - 2 * wt;
        if (maxHoleW < 1 || maxHoleD < 1) {
            return Optional.of("wall_thickness=" + wt + " leaves no courtyard space in "
                    + width + "x" + depth);
        }
        if (holeWidth > maxHoleW || holeDepth > maxHoleD) {
            return Optional.of("courtyard_ratio=" + ratio + " implies hole "
                    + holeWidth + "x" + holeDepth + " but max with wall_thickness=" + wt
                    + " is " + maxHoleW + "x" + maxHoleD);
        }
        return Optional.empty();
    }

    private static boolean isSolidMass(Map<String, Object> params) {
        Double voidRatio = ComponentParamParsers.doubleOrNull(params, "void_ratio", "voidRatio");
        if (voidRatio != null && voidRatio <= 0) {
            return true;
        }
        Object hollow = params.get("hollow");
        return Boolean.FALSE.equals(hollow);
    }

    private static boolean isCourtyardPlan(Map<String, Object> params, List<String> features) {
        Optional<String> plan = PlanPatternCatalog.extractExplicit(params);
        if (plan.isPresent()) {
            Optional<ResolvedFootprint.PlanPattern> parsed = PlanPatternCatalog.parseSupported(plan.get());
            if (parsed.isPresent() && parsed.get() == ResolvedFootprint.PlanPattern.COURTYARD) {
                return true;
            }
        }
        if (features != null) {
            for (String f : features) {
                if (f == null) {
                    continue;
                }
                String lower = f.toLowerCase(Locale.ROOT);
                if (lower.contains("courtyard") || lower.contains("siheyuan") || lower.contains("庭院")
                        || lower.contains("compound") || lower.contains("ring")) {
                    return true;
                }
            }
        }
        return firstPresentKey(params, "courtyard_ratio", "courtyardRatio", "court_ratio") != null;
    }

    private static String firstPresentKey(Map<String, Object> params, String... keys) {
        if (params == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key != null && params.containsKey(key) && params.get(key) != null) {
                return key;
            }
        }
        return null;
    }

    private static Integer parseIntStrict(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d) || d != Math.rint(d)) {
                return null;
            }
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double parseDoubleStrict(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer firstPositive(Integer... values) {
        if (values == null) {
            return null;
        }
        for (Integer v : values) {
            if (v != null && v > 0) {
                return v;
            }
        }
        return null;
    }

    private static String firstString(Map<String, Object> params, String... keys) {
        if (params == null || keys == null) {
            return null;
        }
        for (String key : keys) {
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
