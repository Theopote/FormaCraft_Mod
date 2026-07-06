package com.formacraft.server.generation.structure.blueprint;

import com.formacraft.server.generation.structure.util.StructureSpecParsers;
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
 * TempleOfHeavenBlueprintCompiler (v1):
 * Compiles a semantic "temple_of_heaven" blueprint into a GeneratorBackedPlan
 * routed via radial_terrace_hall typology interpreter.
 * <p>
 * Suggested blueprint keys:
 * - blueprint_type: "temple_of_heaven"
 * - overall_dimensions: { radius/baseRadius, tiers, height }
 * - parameters/features: { hallRadius, hallHeight, detailLevel, blocks... }
 */
public final class TempleOfHeavenBlueprintCompiler implements BlueprintCompiler {
    @Override
    public String id() {
        return "temple_of_heaven_v1";
    }

    @Override
    public boolean supports(BuildingSpec parentSpec, Map<String, Object> blueprint) {
        if (blueprint == null) return false;
        String t = asLower(blueprint.get("blueprint_type"));
        if (t.isBlank()) t = asLower(blueprint.get("blueprintType"));
        if (t.isBlank()) t = asLower(blueprint.get("type"));
        return t.contains("temple_of_heaven") || t.contains("temple") || t.contains("heaven") || t.contains("tiantan");
    }

    @Override
    public SkeletonPlan compile(BuildingSpec parentSpec, Map<String, Object> blueprint) {
        if (blueprint == null) return null;

        Map<String, Object> overall = asMap(blueprint.get("overall_dimensions"));
        if (overall == null) overall = asMap(blueprint.get("overallDimensions"));
        Map<String, Object> params = asMap(blueprint.get("parameters"));
        if (params == null) params = asMap(blueprint.get("features")); // tolerate

        int baseRadius = 18;
        if (overall != null) {
            int r0 = getInt(overall.get("radius"), -1);
            if (r0 <= 0) r0 = getInt(overall.get("baseRadius"), -1);
            if (r0 > 0) baseRadius = r0;
        }
        baseRadius = clamp(baseRadius, 10, 80);

        int height = 28;
        if (overall != null) height = getInt(overall.get("height"), height);
        height = clamp(height, 18, 120);

        // Child spec routed via radial_terrace_hall typology
        BuildingSpec s = new BuildingSpec();
        s.setType(BuildingType.CUSTOM);
        s.setStyle(parentSpec != null && parentSpec.getStyle() != null ? parentSpec.getStyle() : BuildingStyle.ASIAN);
        s.setFootprint(new Footprint(baseRadius));
        s.setHeight(height);
        if (parentSpec != null && parentSpec.getMaterials() != null) s.setMaterials(parentSpec.getMaterials());
        if (parentSpec != null && parentSpec.getFeatures() != null) s.setFeatures(parentSpec.getFeatures());
        if (parentSpec != null && parentSpec.getStyleOptions() != null) s.setStyleOptions(parentSpec.getStyleOptions());

        Map<String, Object> extra = copyExtraWithoutBlueprint(parentSpec != null ? parentSpec.getExtra() : null);
        if (extra == null) extra = new HashMap<>();
        extra.putIfAbsent("typology_id", "radial_terrace_hall");
        extra.putIfAbsent("structural_typology", "radial_terrace_hall");
        extra.putIfAbsent("reference_landmark", "temple_of_heaven");

        // Map a few well-known generator params
        extra.putIfAbsent("baseRadius", baseRadius);
        if (overall != null) {
            copyIfPresent(overall, extra, "tiers", "tiers");
            copyIfPresent(overall, extra, "hallRadius", "hallRadius");
            copyIfPresent(overall, extra, "hallHeight", "hallHeight");
        }
        if (params != null) {
            copyIfPresent(params, extra, "tiers", "tiers");
            copyIfPresent(params, extra, "hallRadius", "hallRadius");
            copyIfPresent(params, extra, "hallHeight", "hallHeight");
            copyIfPresent(params, extra, "detailLevel", "detailLevel");

            // Optional material overrides supported by RadialTerraceHallBuilder
            copyIfPresent(params, extra, "baseBlock", "baseBlock");
            copyIfPresent(params, extra, "stairBlock", "stairBlock");
            copyIfPresent(params, extra, "trimBlock", "trimBlock");
            copyIfPresent(params, extra, "pillarBlock", "pillarBlock");
            copyIfPresent(params, extra, "wallBlock", "wallBlock");
            copyIfPresent(params, extra, "roofBlock", "roofBlock");
            copyIfPresent(params, extra, "accentBlock", "accentBlock");
        }

        // Allow shorthand top-level keys too
        copyIfPresent(blueprint, extra, "detailLevel", "detailLevel");

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

    private static int getInt(Object v, int def) {
        return StructureSpecParsers.intValue(v, def);
    }

    private static int clamp(int v, int min, int max) {
        if (v < min) return min;
        return Math.min(v, max);
    }
}


