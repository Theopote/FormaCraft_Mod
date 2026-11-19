package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Detects landmark-named plans that arrived as FBD/ASSEMBLY office rings.
 * Never silently replaces AI assemblies with a fixed-dimension STRUCTURE module
 * (DESIGN_OVERRIDE). Explicit module/typology/preset opt-in or already-routed
 * landmark STRUCTURE is left alone; otherwise emit capability_gap for replan.
 */
final class LandmarkMisrouteRepair {
    private LandmarkMisrouteRepair() {}

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) return plan;
        String landmark = detectLandmark(plan);
        if (landmark == null) return plan;
        if (alreadyLandmarkRouted(plan, landmark) || explicitLandmarkOptIn(plan, landmark)) {
            return plan;
        }
        if (!looksMisrouted(plan)) return plan;

        FormacraftMod.LOGGER.info(
                "LandmarkMisrouteRepair: refusing silent STRUCTURE rewrite for landmark={}", landmark);
        return PlanComponentRewrite.withCapabilityGap(plan, new CapabilityGap(
                "E_LANDMARK_ROUTE_UNSUPPORTED",
                "地标「" + landmark + "」方案为 ASSEMBLY/FBD 组合，已拒绝用固定尺寸 STRUCTURE 模板覆盖；请保留开放组合并修正路由，或显式 module/typology/preset 选择地标模块",
                "LandmarkMisrouteRepair",
                List.of(
                        "Keep authored height/width on the open composition",
                        "Or opt in with module_id / typology: / preset: for " + landmark,
                        "Re-plan landmark geometry without office/atrium rings")));
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

    /** Explicit user/plan opt-in to the fixed landmark executor. */
    private static boolean explicitLandmarkOptIn(LlmPlan plan, String landmark) {
        String blob = blob(plan);
        String lower = landmark.toLowerCase(Locale.ROOT);
        if (blob.contains("module:" + lower)
                || blob.contains("module_id=" + lower)
                || blob.contains("typology:" + lower)
                || blob.contains("preset:" + lower)
                || blob.contains("preset:<" + lower)
                || blob.contains("landmark:" + lower)) {
            return true;
        }
        for (Component c : plan.components()) {
            if (c == null || c.params() == null) continue;
            for (String key : List.of("module_id", "landmark", "reference_landmark",
                    "typology_id", "typology", "preset_id")) {
                Object v = c.params().get(key);
                if (v != null && String.valueOf(v).toLowerCase(Locale.ROOT).contains(lower)) {
                    return true;
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
        if (plan.planMeta() != null) {
            Object text = plan.planMeta().get("request_intent_text");
            if (text != null) sb.append(text).append(' ');
            Object ri = plan.planMeta().get("request_intent");
            if (ri instanceof Map<?, ?> map && map.get("original_text") != null) {
                sb.append(map.get("original_text")).append(' ');
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
                if (c.params() != null) {
                    for (String key : List.of("component_id", "module_id", "landmark",
                            "reference_landmark", "typology_id", "typology", "preset_id")) {
                        Object v = c.params().get(key);
                        if (v != null) sb.append(v).append(' ');
                    }
                }
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
