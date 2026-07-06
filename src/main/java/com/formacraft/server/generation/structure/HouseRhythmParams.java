package com.formacraft.server.generation.structure;

import com.formacraft.common.facade.rhythm.RepeatingPattern;
import com.formacraft.common.facade.rhythm.RepeatingPatternParser;
import com.formacraft.common.generation.component.util.ComponentFacadeRhythmPlanner;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.style.StyleGenome;
import com.formacraft.common.style.profile.StyleProfile;

import java.util.HashMap;
import java.util.Map;

/**
 * Builds rhythm planner params for {@link HouseGenerator} from BuildingSpec / profile / genome.
 */
public final class HouseRhythmParams {

    private HouseRhythmParams() {}

    public static Map<String, Object> build(BuildingSpec spec, StyleProfile profile, StyleGenome genome) {
        Map<String, Object> params = new HashMap<>();
        if (spec != null && spec.getExtra() != null) {
            copyIfPresent(spec.getExtra(), params, "repeating_pattern", "repeatingPattern");
            copyIfPresent(spec.getExtra(), params, "rhythm_preset", "rhythmPreset", "facade_rhythm_preset");
            copyIfPresent(spec.getExtra(), params, "rhythm");
            copyIfPresent(spec.getExtra(), params, "facade_profile", "facadeProfile", "facade");
        }
        if (profile != null && profile.details() != null) {
            if (!params.containsKey("facade_profile") && profile.details().facadeProfile != null) {
                params.put("facade_profile", profile.details().facadeProfile.trim());
            }
        }
        if (genome != null && genome.form != null && genome.form.rhythm != null && !params.containsKey("rhythm")) {
            params.put("rhythm", genome.form.rhythm);
        }
        if (RepeatingPatternParser.parse(params) == null && !hasRhythmPreset(params)) {
            params.put("repeating_pattern", RepeatingPatternParser.toParamsMap(RepeatingPattern.classicalPilasterBay()));
        }
        return params;
    }

    public static ComponentFacadeRhythmPlanner.RhythmPlan resolveWidthPlan(
            BuildingSpec spec, StyleProfile profile, StyleGenome genome, int width) {
        return ComponentFacadeRhythmPlanner.resolve(null, build(spec, profile, genome), width);
    }

    public static ComponentFacadeRhythmPlanner.RhythmPlan resolveDepthPlan(
            BuildingSpec spec, StyleProfile profile, StyleGenome genome, int depth) {
        return ComponentFacadeRhythmPlanner.resolve(null, build(spec, profile, genome), depth);
    }

    private static boolean hasRhythmPreset(Map<String, Object> params) {
        Object v = first(params, "rhythm_preset", "rhythmPreset", "facade_rhythm_preset");
        return v != null && !String.valueOf(v).isBlank();
    }

    private static void copyIfPresent(Map<String, Object> src, Map<String, Object> dst, String... keys) {
        Object v = first(src, keys);
        if (v != null) {
            dst.put(keys[0], v);
        }
    }

    private static Object first(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            if (key == null) {
                continue;
            }
            Object v = map.get(key);
            if (v != null) {
                return v;
            }
        }
        return null;
    }
}
