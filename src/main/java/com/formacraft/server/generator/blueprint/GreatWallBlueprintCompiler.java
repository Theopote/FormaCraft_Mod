package com.formacraft.server.generator.blueprint;

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
 * GreatWallBlueprintCompiler (v1):
 * Compiles a semantic "great_wall" blueprint into a GeneratorBackedPlan
 * that delegates to GreatWallGenerator via landmark routing.
 *
 * Suggested blueprint keys:
 * - blueprint_type: "great_wall"
 * - overall_dimensions: { length, height, thickness, facing }
 * - parameters/features: { towerSpacing, followTerrain, mixWallBlocks, paletteId }
 */
public final class GreatWallBlueprintCompiler implements BlueprintCompiler {
    @Override
    public String id() {
        return "great_wall_v1";
    }

    @Override
    public boolean supports(BuildingSpec parentSpec, Map<String, Object> blueprint) {
        if (blueprint == null) return false;
        String t = asLower(blueprint.get("blueprint_type"));
        if (t.isBlank()) t = asLower(blueprint.get("blueprintType"));
        if (t.isBlank()) t = asLower(blueprint.get("type"));
        return t.contains("great_wall") || t.contains("greatwall") || t.contains("wall") || t.contains("changcheng");
    }

    @Override
    public SkeletonPlan compile(BuildingSpec parentSpec, Map<String, Object> blueprint) {
        if (blueprint == null) return null;

        Map<String, Object> overall = asMap(blueprint.get("overall_dimensions"));
        if (overall == null) overall = asMap(blueprint.get("overallDimensions"));
        Map<String, Object> params = asMap(blueprint.get("parameters"));
        if (params == null) params = asMap(blueprint.get("features")); // tolerate

        int length = 120;
        int height = 10;
        int thickness = 5;
        String facing = "EAST";

        if (overall != null) {
            length = getInt(overall.get("length"), length);
            height = getInt(overall.get("height"), height);
            thickness = getInt(overall.get("thickness"), thickness);
            String f = asString(overall.get("facing"));
            if (f != null && !f.isBlank()) facing = f.trim().toUpperCase(Locale.ROOT);
        }
        length = clamp(length, 20, 2000);
        height = clamp(height, 5, 80);
        thickness = clamp(thickness, 3, 21);

        BuildingSpec s = new BuildingSpec();
        s.setType(BuildingType.WALL);
        s.setStyle(parentSpec != null && parentSpec.getStyle() != null ? parentSpec.getStyle() : BuildingStyle.MEDIEVAL);
        // GreatWallGenerator uses width->thickness and depth->length as fallback
        s.setFootprint(new Footprint(thickness, length));
        s.setHeight(height);
        if (parentSpec != null && parentSpec.getMaterials() != null) s.setMaterials(parentSpec.getMaterials());
        if (parentSpec != null && parentSpec.getFeatures() != null) s.setFeatures(parentSpec.getFeatures());
        if (parentSpec != null && parentSpec.getStyleOptions() != null) s.setStyleOptions(parentSpec.getStyleOptions());

        Map<String, Object> extra = copyExtraWithoutBlueprint(parentSpec != null ? parentSpec.getExtra() : null);
        if (extra == null) extra = new HashMap<>();
        extra.putIfAbsent("landmark", "great_wall");

        // Explicit params used by GreatWallGenerator
        extra.putIfAbsent("wallLength", length);
        extra.putIfAbsent("wallHeight", height);
        extra.putIfAbsent("wallThickness", thickness);
        extra.putIfAbsent("facing", facing);

        if (params != null) {
            copyIfPresent(params, extra, "towerSpacing", "towerSpacing");
            copyIfPresent(params, extra, "followTerrain", "followTerrain");
            copyIfPresent(params, extra, "mixWallBlocks", "mixWallBlocks");
            copyIfPresent(params, extra, "paletteId", "paletteId");

            // Optional material overrides used by GreatWallGenerator
            copyIfPresent(params, extra, "wallBlock", "wallBlock");
            copyIfPresent(params, extra, "accentBlock", "accentBlock");
            copyIfPresent(params, extra, "walkwayBlock", "walkwayBlock");
            copyIfPresent(params, extra, "crenelBlock", "crenelBlock");
            copyIfPresent(params, extra, "towerBlock", "towerBlock");
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


