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
 * FoguangTempleHallBlueprintCompiler (v1): routes to tailiang_timber_hall typology interpreter.
 */
public final class FoguangTempleHallBlueprintCompiler implements BlueprintCompiler {

    @Override
    public String id() {
        return "foguang_temple_hall_v1";
    }

    @Override
    public boolean supports(BuildingSpec parentSpec, Map<String, Object> blueprint) {
        if (blueprint == null) return false;
        String t = typeLower(blueprint);
        if (t.contains("foguang") || t.contains("foguang_temple")) return true;
        if (parentSpec != null && parentSpec.getExtra() != null) {
            Object lm = parentSpec.getExtra().get("landmark");
            if (lm != null && String.valueOf(lm).toLowerCase(Locale.ROOT).contains("foguang")) return true;
        }
        return false;
    }

    @Override
    public SkeletonPlan compile(BuildingSpec parentSpec, Map<String, Object> blueprint) {
        Map<String, Object> overall = mapAny(blueprint.get("overall_dimensions"));
        if (overall == null) overall = mapAny(blueprint.get("overallDimensions"));
        Map<String, Object> params = mapAny(blueprint.get("parameters"));
        if (params == null) params = mapAny(blueprint.get("features"));

        int baysX = 7;
        int baysZ = 4;
        int width = 21;
        int depth = 15;
        int hallHeight = 7;
        String facing = "SOUTH";
        if (overall != null) {
            baysX = StructureSpecParsers.intValue(overall.get("baysX"), baysX);
            baysZ = StructureSpecParsers.intValue(overall.get("baysZ"), baysZ);
            width = StructureSpecParsers.intValue(overall.get("width"), width);
            depth = StructureSpecParsers.intValue(overall.get("depth"), depth);
            hallHeight = StructureSpecParsers.intValue(first(overall.get("hallHeight"), overall.get("height")), hallHeight);
            String f = StructureSpecParsers.stringValue(overall.get("facing"), facing);
            if (f != null && !f.isBlank()) facing = f.trim().toUpperCase(Locale.ROOT);
        }

        BuildingSpec s = new BuildingSpec();
        s.setType(BuildingType.HOUSE);
        s.setStyle(parentSpec != null && parentSpec.getStyle() != null ? parentSpec.getStyle() : BuildingStyle.ASIAN);
        s.setFootprint(new Footprint(Math.max(15, width), Math.max(11, depth)));
        s.setHeight(Math.max(8, hallHeight + 6));
        s.setFloors(1);

        Map<String, Object> extra = copyExtra(parentSpec);
        extra.putIfAbsent("typology_id", "tailiang_timber_hall");
        extra.putIfAbsent("structural_typology", "tailiang_timber_hall");
        extra.putIfAbsent("reference_landmark", "foguang_temple_hall");
        extra.putIfAbsent("baysX", baysX);
        extra.putIfAbsent("baysZ", baysZ);
        extra.putIfAbsent("width", width);
        extra.putIfAbsent("depth", depth);
        extra.putIfAbsent("hallHeight", hallHeight);
        extra.putIfAbsent("facing", facing);
        extra.putIfAbsent("includeSubEaves", true);
        if (params != null) {
            copy(params, extra, "includeSubEaves");
            copy(params, extra, "subEavesDepth");
            copy(params, extra, "platformHeight");
            copy(params, extra, "bayWidth");
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
