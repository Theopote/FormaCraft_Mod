package com.formacraft.common.generation.structure.blueprint;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.model.build.BuildingType;
import com.formacraft.common.model.build.Footprint;
import com.formacraft.common.skeleton.SkeletonPlan;
import com.formacraft.common.skeleton.compound.GeneratorBackedPlan;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * TulouBlueprintCompiler (v1):
 * Compiles a semantic tulou blueprint into a GeneratorBackedPlan that delegates to TulouGenerator.
 *
 * Expected blueprint keys (flexible):
 * - blueprint_type: "tulou" (recommended)
 * - overall_dimensions: { diameter | radius, floors?, door_facing? }
 * - parameters/features: { ringThickness?, courtyardRatio?, courtyardRadius?, detailLevel?, windowShutter? }
 */
public final class TulouBlueprintCompiler implements BlueprintCompiler {
    @Override
    public String id() {
        return "tulou_v1";
    }

    @Override
    public boolean supports(BuildingSpec parentSpec, Map<String, Object> blueprint) {
        if (blueprint == null) return false;
        String t = asLower(blueprint.get("blueprint_type"));
        if (t.isBlank()) t = asLower(blueprint.get("blueprintType"));
        if (t.isBlank()) t = asLower(blueprint.get("type"));
        if (!t.isBlank()) {
            return t.contains("tulou") || t.contains("earthen") || t.contains("hakka") || t.contains("ring");
        }
        // Heuristic: overall_dimensions has diameter/radius + shape circle
        Map<String, Object> overall = asMap(blueprint.get("overall_dimensions"));
        if (overall == null) overall = asMap(blueprint.get("overallDimensions"));
        if (overall != null) {
            String shape = asLower(overall.get("shape"));
            boolean circle = shape.contains("circle") || shape.contains("radial");
            boolean hasDia = overall.containsKey("diameter") || overall.containsKey("radius");
            return circle && hasDia;
        }
        return false;
    }

    @Override
    public SkeletonPlan compile(BuildingSpec parentSpec, Map<String, Object> blueprint) {
        if (blueprint == null) return null;

        Map<String, Object> overall = asMap(blueprint.get("overall_dimensions"));
        if (overall == null) overall = asMap(blueprint.get("overallDimensions"));

        int radius = 10;
        if (overall != null) {
            // prefer explicit radius/diameter
            int r0 = getInt(overall.get("radius"), -1);
            int d0 = getInt(overall.get("diameter"), -1);
            if (r0 > 0) radius = r0;
            else if (d0 > 0) radius = Math.max(3, d0 / 2);
        }
        // fallback to parent footprint if present
        if (parentSpec != null && parentSpec.getFootprint() != null && parentSpec.getFootprint().getRadius() > 0) {
            radius = Math.max(radius, parentSpec.getFootprint().getRadius());
        }
        radius = clamp(radius, 6, 80);

        int floors = 3;
        if (overall != null) floors = getInt(overall.get("floors"), floors);
        if (parentSpec != null && parentSpec.getFloors() > 0) floors = Math.max(floors, parentSpec.getFloors());
        floors = clamp(floors, 1, 8);

        // Build a child spec that routes to TulouGenerator via landmark
        BuildingSpec s = new BuildingSpec();
        s.setType(BuildingType.HOUSE);
        s.setStyle(parentSpec != null && parentSpec.getStyle() != null ? parentSpec.getStyle() : BuildingStyle.ASIAN);
        s.setFootprint(new Footprint(radius));
        s.setFloors(floors);
        s.setHeight(Math.max(8, floors * 4 + 6));
        if (parentSpec != null && parentSpec.getMaterials() != null) s.setMaterials(parentSpec.getMaterials());
        if (parentSpec != null && parentSpec.getFeatures() != null) s.setFeatures(parentSpec.getFeatures());
        if (parentSpec != null && parentSpec.getStyleOptions() != null) s.setStyleOptions(parentSpec.getStyleOptions());

        Map<String, Object> extra = copyExtraWithoutBlueprint(parentSpec != null ? parentSpec.getExtra() : null);
        if (extra == null) extra = new HashMap<>();
        // stable routing
        extra.putIfAbsent("landmark", "tulou");

        // common parameters
        Map<String, Object> params = asMap(blueprint.get("parameters"));
        if (params == null) params = asMap(blueprint.get("features")); // tolerate
        if (params != null) {
            copyIfPresent(params, extra, "ringThickness", "ringThickness");
            copyIfPresent(params, extra, "courtyardRadius", "courtyardRadius");
            copyIfPresent(params, extra, "courtyardRatio", "courtyardRatio");
            copyIfPresent(params, extra, "detailLevel", "detailLevel");
            copyIfPresent(params, extra, "windowShutter", "windowShutter");
            copyIfPresent(params, extra, "windowShutterOpen", "windowShutterOpen");
            copyIfPresent(params, extra, "windowShutterBlock", "windowShutterBlock");
        }

        // door facing: allow several keys
        String doorFacing = null;
        if (overall != null) {
            doorFacing = firstNonBlank(asString(overall.get("door_facing")), asString(overall.get("doorFacing")));
        }
        if (doorFacing == null) {
            doorFacing = firstNonBlank(asString(blueprint.get("door_facing")), asString(blueprint.get("doorFacing")));
        }
        if (doorFacing != null && !doorFacing.isBlank()) {
            extra.putIfAbsent("doorFacing", doorFacing.trim().toUpperCase(Locale.ROOT));
        }

        s.setExtra(extra);
        return new GeneratorBackedPlan(s);
    }

    private static Map<String, Object> copyExtraWithoutBlueprint(Map<String, Object> extra) {
        if (extra == null || extra.isEmpty()) return extra;
        HashMap<String, Object> copy = new HashMap<>(extra);
        copy.remove("blueprint");
        copy.remove("blueprint_json");
        copy.remove("blueprintJson");
        return copy;
    }

    private static void copyIfPresent(Map<String, Object> src, Map<String, Object> dst, String srcKey, String dstKey) {
        if (src == null || dst == null) return;
        if (!src.containsKey(srcKey)) return;
        if (!dst.containsKey(dstKey)) dst.put(dstKey, src.get(srcKey));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        if (v instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return null;
    }

    private static String asLower(Object v) {
        return v == null ? "" : String.valueOf(v).trim().toLowerCase(Locale.ROOT);
    }

    private static String asString(Object v) {
        return v == null ? null : String.valueOf(v).trim();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    private static int getInt(Object v, int def) {
        if (v == null) return def;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception ignored) {
            return def;
        }
    }

    private static int clamp(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }
}


