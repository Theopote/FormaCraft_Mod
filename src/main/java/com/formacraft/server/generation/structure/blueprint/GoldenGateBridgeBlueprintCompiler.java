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
 * GoldenGateBridgeBlueprintCompiler (v1):
 * Compiles a semantic suspension bridge blueprint into a GeneratorBackedPlan
 * routed via suspension_bridge typology interpreter.
 *
 * Suggested blueprint keys:
 * - blueprint_type: "golden_gate_bridge"
 * - overall_dimensions: { span, deckWidth, towerHeight, facing }
 * - parameters/features: { followTerrain, detailLevel, blocks... }
 */
public final class GoldenGateBridgeBlueprintCompiler implements BlueprintCompiler {
    @Override
    public String id() {
        return "golden_gate_bridge_v1";
    }

    @Override
    public boolean supports(BuildingSpec parentSpec, Map<String, Object> blueprint) {
        if (blueprint == null) return false;
        String t = asLower(blueprint.get("blueprint_type"));
        if (t.isBlank()) t = asLower(blueprint.get("blueprintType"));
        if (t.isBlank()) t = asLower(blueprint.get("type"));
        return t.contains("golden_gate") || t.contains("goldengate") || t.contains("suspension_bridge");
    }

    @Override
    public SkeletonPlan compile(BuildingSpec parentSpec, Map<String, Object> blueprint) {
        if (blueprint == null) return null;

        Map<String, Object> overall = asMap(blueprint.get("overall_dimensions"));
        if (overall == null) overall = asMap(blueprint.get("overallDimensions"));
        Map<String, Object> params = asMap(blueprint.get("parameters"));
        if (params == null) params = asMap(blueprint.get("features"));

        int span = 180;
        int deckWidth = 9;
        int towerHeight = 44;
        String facing = "EAST";
        Boolean followTerrain = null;

        if (overall != null) {
            span = getInt(overall.get("span"), span);
            deckWidth = getInt(overall.get("deckWidth"), deckWidth);
            towerHeight = getInt(overall.get("towerHeight"), towerHeight);
            String f = asString(overall.get("facing"));
            if (f != null && !f.isBlank()) facing = f.trim().toUpperCase(Locale.ROOT);
        }
        if (params != null) {
            Object ft = params.get("followTerrain");
            if (ft != null) followTerrain = asBool(ft);
        }

        BuildingSpec s = new BuildingSpec();
        s.setType(BuildingType.BRIDGE);
        s.setStyle(parentSpec != null && parentSpec.getStyle() != null ? parentSpec.getStyle() : BuildingStyle.MODERN);
        s.setFootprint(new Footprint(Math.max(5, deckWidth), Math.max(40, span)));
        s.setHeight(Math.max(18, towerHeight));

        Map<String, Object> extra = copyExtraWithoutBlueprint(parentSpec != null ? parentSpec.getExtra() : null);
        if (extra == null) extra = new HashMap<>();
        extra.putIfAbsent("typology_id", "suspension_bridge");
        extra.putIfAbsent("structural_typology", "suspension_bridge");
        extra.putIfAbsent("reference_landmark", "golden_gate_bridge");
        extra.putIfAbsent("span", span);
        extra.putIfAbsent("deckWidth", deckWidth);
        extra.putIfAbsent("towerHeight", towerHeight);
        extra.putIfAbsent("facing", facing);
        if (followTerrain != null) extra.putIfAbsent("followTerrain", followTerrain);

        if (params != null) {
            copyIfPresent(params, extra, "detailLevel", "detailLevel");
            copyIfPresent(params, extra, "towerBlock", "towerBlock");
            copyIfPresent(params, extra, "deckBlock", "deckBlock");
            copyIfPresent(params, extra, "cableBlock", "cableBlock");
            copyIfPresent(params, extra, "hangerBlock", "hangerBlock");
            copyIfPresent(params, extra, "railBlock", "railBlock");
            copyIfPresent(params, extra, "foundationBlock", "foundationBlock");
        }
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

    private static String asString(Object v) {
        return v == null ? null : String.valueOf(v).trim();
    }

    private static int getInt(Object v, int def) {
        return StructureSpecParsers.intValue(v, def);
    }

    private static Boolean asBool(Object v) {
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        if (s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on")) return true;
        if (s.equals("false") || s.equals("0") || s.equals("no") || s.equals("n") || s.equals("off")) return false;
        return null;
    }
}


