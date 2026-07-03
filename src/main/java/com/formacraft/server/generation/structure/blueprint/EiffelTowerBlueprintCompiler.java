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
 * EiffelTowerBlueprintCompiler (v1):
 * Delegates to EiffelTowerGenerator via landmark routing.
 *
 * Suggested blueprint keys:
 * - blueprint_type: "eiffel_tower"
 * - overall_dimensions: { height/towerHeight, baseWidth, platformCount }
 * - parameters/features: { detailLevel, legBlock/braceBlock/platformBlock/railBlock/spireBlock }
 */
public final class EiffelTowerBlueprintCompiler implements BlueprintCompiler {
    @Override
    public String id() {
        return "eiffel_tower_v1";
    }

    @Override
    public boolean supports(BuildingSpec parentSpec, Map<String, Object> blueprint) {
        if (blueprint == null) return false;
        String t = asLower(blueprint.get("blueprint_type"));
        if (t.isBlank()) t = asLower(blueprint.get("blueprintType"));
        if (t.isBlank()) t = asLower(blueprint.get("type"));
        return t.contains("eiffel") || t.contains("eiffel_tower");
    }

    @Override
    public SkeletonPlan compile(BuildingSpec parentSpec, Map<String, Object> blueprint) {
        if (blueprint == null) return null;

        Map<String, Object> overall = asMap(blueprint.get("overall_dimensions"));
        if (overall == null) overall = asMap(blueprint.get("overallDimensions"));
        Map<String, Object> params = asMap(blueprint.get("parameters"));
        if (params == null) params = asMap(blueprint.get("features"));

        int height = 60;
        int baseWidth = 27;
        int platformCount = 2;

        if (overall != null) {
            height = getInt(firstNonNull(overall.get("towerHeight"), overall.get("height")), height);
            baseWidth = getInt(overall.get("baseWidth"), baseWidth);
            platformCount = getInt(overall.get("platformCount"), platformCount);
        }

        BuildingSpec s = new BuildingSpec();
        s.setType(BuildingType.TOWER);
        s.setStyle(parentSpec != null && parentSpec.getStyle() != null ? parentSpec.getStyle() : BuildingStyle.MODERN);
        // footprint fallback influences baseWidth in generator; use square footprint as a hint.
        s.setFootprint(new Footprint(Math.max(9, baseWidth), Math.max(9, baseWidth)));
        s.setHeight(height);

        Map<String, Object> extra = copyExtraWithoutBlueprint(parentSpec != null ? parentSpec.getExtra() : null);
        if (extra == null) extra = new HashMap<>();
        extra.putIfAbsent("landmark", "eiffel_tower");
        extra.putIfAbsent("towerHeight", height);
        extra.putIfAbsent("baseWidth", baseWidth);
        extra.putIfAbsent("platformCount", platformCount);

        if (params != null) {
            copyIfPresent(params, extra, "detailLevel", "detailLevel");
            copyIfPresent(params, extra, "legBlock", "legBlock");
            copyIfPresent(params, extra, "braceBlock", "braceBlock");
            copyIfPresent(params, extra, "platformBlock", "platformBlock");
            copyIfPresent(params, extra, "railBlock", "railBlock");
            copyIfPresent(params, extra, "spireBlock", "spireBlock");
        }
        copyIfPresent(blueprint, extra, "detailLevel", "detailLevel");

        s.setExtra(extra);
        return new GeneratorBackedPlan(s);
    }

    private static Object firstNonNull(Object a, Object b) {
        return a != null ? a : b;
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
}


