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
 * GiantWildGoosePagodaBlueprintCompiler (v1):
 * Delegates to GiantWildGoosePagodaGenerator via landmark routing.
 *
 * Suggested blueprint keys:
 * - blueprint_type: "giant_wild_goose_pagoda"
 * - overall_dimensions: { levels, towerHeight/height, baseWidth, facing }
 * - parameters/features: { detailLevel, bodyBlock/trimBlock/eaveBlock/accentBlock }
 */
public final class GiantWildGoosePagodaBlueprintCompiler implements BlueprintCompiler {
    @Override
    public String id() {
        return "giant_wild_goose_pagoda_v1";
    }

    @Override
    public boolean supports(BuildingSpec parentSpec, Map<String, Object> blueprint) {
        if (blueprint == null) return false;
        String t = asLower(blueprint.get("blueprint_type"));
        if (t.isBlank()) t = asLower(blueprint.get("blueprintType"));
        if (t.isBlank()) t = asLower(blueprint.get("type"));
        return t.contains("giant_wild_goose_pagoda") || t.contains("wild_goose") || t.contains("pagoda") || t.contains("dayanta");
    }

    @Override
    public SkeletonPlan compile(BuildingSpec parentSpec, Map<String, Object> blueprint) {
        if (blueprint == null) return null;

        Map<String, Object> overall = asMap(blueprint.get("overall_dimensions"));
        if (overall == null) overall = asMap(blueprint.get("overallDimensions"));
        Map<String, Object> params = asMap(blueprint.get("parameters"));
        if (params == null) params = asMap(blueprint.get("features"));

        int levels = 7;
        int towerHeight = 42;
        int baseWidth = 17;
        String facing = "SOUTH";

        if (overall != null) {
            levels = getInt(overall.get("levels"), levels);
            towerHeight = getInt(firstNonNull(overall.get("towerHeight"), overall.get("height")), towerHeight);
            baseWidth = getInt(overall.get("baseWidth"), baseWidth);
            String f = asString(overall.get("facing"));
            if (f != null && !f.isBlank()) facing = f.trim().toUpperCase(Locale.ROOT);
        }

        BuildingSpec s = new BuildingSpec();
        s.setType(BuildingType.TOWER);
        s.setStyle(parentSpec != null && parentSpec.getStyle() != null ? parentSpec.getStyle() : BuildingStyle.ASIAN);
        s.setFootprint(new Footprint(Math.max(9, baseWidth), Math.max(9, baseWidth)));
        s.setHeight(Math.max(18, towerHeight));
        s.setFloors(Math.max(3, levels));

        Map<String, Object> extra = copyExtraWithoutBlueprint(parentSpec != null ? parentSpec.getExtra() : null);
        if (extra == null) extra = new HashMap<>();
        extra.putIfAbsent("landmark", "giant_wild_goose_pagoda");
        extra.putIfAbsent("levels", levels);
        extra.putIfAbsent("towerHeight", towerHeight);
        extra.putIfAbsent("baseWidth", baseWidth);
        extra.putIfAbsent("facing", facing);

        if (params != null) {
            copyIfPresent(params, extra, "detailLevel", "detailLevel");
            copyIfPresent(params, extra, "bodyBlock", "bodyBlock");
            copyIfPresent(params, extra, "trimBlock", "trimBlock");
            copyIfPresent(params, extra, "eaveBlock", "eaveBlock");
            copyIfPresent(params, extra, "accentBlock", "accentBlock");
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

    private static String asString(Object v) {
        return v == null ? null : String.valueOf(v).trim();
    }

    private static int getInt(Object v, int def) {
        return StructureSpecParsers.intValue(v, def);
    }
}


