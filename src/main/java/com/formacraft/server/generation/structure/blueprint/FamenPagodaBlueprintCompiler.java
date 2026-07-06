package com.formacraft.server.generation.structure.blueprint;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.model.build.BuildingType;
import com.formacraft.common.model.build.Footprint;
import com.formacraft.common.skeleton.SkeletonPlan;
import com.formacraft.common.skeleton.compound.GeneratorBackedPlan;
import com.formacraft.server.generation.structure.util.StructureSpecParsers;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * FamenPagodaBlueprintCompiler (v1): routes to dense_eaves_pagoda typology interpreter.
 */
public final class FamenPagodaBlueprintCompiler implements BlueprintCompiler {

    @Override
    public String id() {
        return "famen_pagoda_v1";
    }

    @Override
    public boolean supports(BuildingSpec parentSpec, Map<String, Object> blueprint) {
        if (blueprint == null) return false;
        String t = typeLower(blueprint);
        if (t.contains("famen") || t.contains("famensi") || t.contains("famen_pagoda")) return true;
        if (parentSpec != null && parentSpec.getExtra() != null) {
            Object lm = parentSpec.getExtra().get("landmark");
            if (lm != null && String.valueOf(lm).toLowerCase(Locale.ROOT).contains("famen")) return true;
        }
        return false;
    }

    @Override
    public SkeletonPlan compile(BuildingSpec parentSpec, Map<String, Object> blueprint) {
        Map<String, Object> overall = mapAny(blueprint.get("overall_dimensions"));
        if (overall == null) overall = mapAny(blueprint.get("overallDimensions"));
        Map<String, Object> params = mapAny(blueprint.get("parameters"));
        if (params == null) params = mapAny(blueprint.get("features"));

        int levels = 13;
        int towerHeight = 47;
        int baseWidth = 10;
        String facing = "SOUTH";
        if (overall != null) {
            levels = StructureSpecParsers.intValue(overall.get("levels"), levels);
            towerHeight = StructureSpecParsers.intValue(first(overall.get("towerHeight"), overall.get("height")), towerHeight);
            baseWidth = StructureSpecParsers.intValue(overall.get("baseWidth"), baseWidth);
            String f = StructureSpecParsers.stringValue(overall.get("facing"), facing);
            if (f != null && !f.isBlank()) facing = f.trim().toUpperCase(Locale.ROOT);
        }

        BuildingSpec s = new BuildingSpec();
        s.setType(BuildingType.TOWER);
        s.setStyle(parentSpec != null && parentSpec.getStyle() != null ? parentSpec.getStyle() : BuildingStyle.ASIAN);
        s.setFootprint(new Footprint(Math.max(7, baseWidth), Math.max(7, baseWidth)));
        s.setHeight(Math.max(28, towerHeight));
        s.setFloors(Math.max(7, levels));

        Map<String, Object> extra = copyExtra(parentSpec);
        extra.putIfAbsent("typology_id", "dense_eaves_pagoda");
        extra.putIfAbsent("structural_typology", "dense_eaves_pagoda");
        extra.putIfAbsent("reference_landmark", "famen_pagoda");
        extra.putIfAbsent("footprint", "octagon");
        extra.putIfAbsent("levels", levels);
        extra.putIfAbsent("towerHeight", towerHeight);
        extra.putIfAbsent("baseWidth", baseWidth);
        extra.putIfAbsent("facing", facing);
        if (params != null) {
            copy(params, extra, "detailLevel");
            copy(params, extra, "bodyBlock");
            copy(params, extra, "trimBlock");
            copy(params, extra, "eaveBlock");
            copy(params, extra, "accentBlock");
        }
        s.setExtra(extra);
        return new GeneratorBackedPlan(s);
    }

    private static String typeLower(Map<String, Object> blueprint) {
        String t = StructureSpecParsers.stringValue(blueprint.get("blueprint_type"), "");
        if (t.isBlank()) t = StructureSpecParsers.stringValue(blueprint.get("blueprintType"), "");
        if (t.isBlank()) t = StructureSpecParsers.stringValue(blueprint.get("type"), "");
        return t.toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapAny(Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    private static Object first(Object a, Object b) {
        return a != null ? a : b;
    }

    private static Map<String, Object> copyExtra(BuildingSpec parentSpec) {
        Map<String, Object> extra = parentSpec != null ? parentSpec.getExtra() : null;
        if (extra == null || extra.isEmpty()) return new HashMap<>();
        HashMap<String, Object> copy = new HashMap<>(extra);
        copy.remove("blueprint");
        copy.remove("blueprint_json");
        copy.remove("blueprintJson");
        return copy;
    }

    private static void copy(Map<String, Object> src, Map<String, Object> dst, String key) {
        if (src.containsKey(key) && !dst.containsKey(key)) dst.put(key, src.get(key));
    }
}
