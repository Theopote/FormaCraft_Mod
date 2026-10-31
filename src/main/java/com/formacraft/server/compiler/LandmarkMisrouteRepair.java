package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Vec3i;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * When the user names a landmark (e.g. Eiffel Tower) but the LLM emits FBD atriums /
 * office rings, rewrite to a STRUCTURE landmark module that knows the silhouette.
 */
final class LandmarkMisrouteRepair {
    private LandmarkMisrouteRepair() {}

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) return plan;
        String landmark = detectLandmark(plan);
        if (landmark == null) return plan;
        if (alreadyLandmarkRouted(plan, landmark)) return plan;
        if (!looksMisrouted(plan)) return plan;

        Map<String, Object> params = new HashMap<>();
        params.put("component_id", landmark);
        params.put("module_id", landmark);
        params.put("landmark", landmark);
        params.put("reference_landmark", landmark);
        if ("eiffel_tower".equals(landmark)) {
            params.put("height", 60);
            params.put("baseWidth", 27);
        }
        Component structure = new Component(
                "STRUCTURE",
                plan.components().getFirst().slotId(),
                new Vec3i(0, 0, 0),
                new Dimensions(27, 27, 60),
                List.of("landmark:" + landmark, "module:" + landmark),
                params);
        FormacraftMod.LOGGER.info(
                "LandmarkMisrouteRepair: rewrite FBD/assembly plan → STRUCTURE landmark={}", landmark);
        return PlanComponentRewrite.withComponents(plan, List.of(structure));
    }

    private static boolean alreadyLandmarkRouted(LlmPlan plan, String landmark) {
        for (Component c : plan.components()) {
            if (c == null) continue;
            if ("STRUCTURE".equalsIgnoreCase(c.componentType())
                    && c.params() != null
                    && landmark.equalsIgnoreCase(String.valueOf(c.params().get("module_id")))) {
                return true;
            }
            if (c.features() != null) {
                for (String f : c.features()) {
                    if (f != null && f.toLowerCase(Locale.ROOT).contains("landmark:" + landmark)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean looksMisrouted(LlmPlan plan) {
        for (Component c : plan.components()) {
            if (c == null || c.params() == null) continue;
            if (c.params().containsKey("fbd_program") || c.params().containsKey("fbdProgram")) return true;
            if ("ASSEMBLY".equalsIgnoreCase(c.componentType())) return true;
            Object id = c.params().get("component_id");
            if (id != null) {
                String s = String.valueOf(id).toLowerCase(Locale.ROOT);
                if (s.contains("atrium") || s.contains("office") || s.contains("ring")) return true;
            }
        }
        return false;
    }

    private static String detectLandmark(LlmPlan plan) {
        String blob = blob(plan);
        if (blob.contains("eiffel") || blob.contains("埃菲尔") || blob.contains("艾菲尔")) {
            return "eiffel_tower";
        }
        return null;
    }

    private static String blob(LlmPlan plan) {
        StringBuilder sb = new StringBuilder();
        if (plan.playerFidelityNoticeZh() != null) sb.append(plan.playerFidelityNoticeZh()).append(' ');
        if (plan.distinguishingFeatures() != null) {
            for (String f : plan.distinguishingFeatures()) {
                if (f != null) sb.append(f).append(' ');
            }
        }
        if (plan.components() != null) {
            for (Component c : plan.components()) {
                if (c == null) continue;
                if (c.features() != null) {
                    for (String f : c.features()) {
                        if (f != null) sb.append(f).append(' ');
                    }
                }
                if (c.params() != null && c.params().get("component_id") != null) {
                    sb.append(c.params().get("component_id")).append(' ');
                }
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
