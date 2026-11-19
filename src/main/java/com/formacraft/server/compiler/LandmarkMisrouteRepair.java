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
 * (DESIGN_OVERRIDE). A semantic landmark feature/tag alone is not an executor —
 * only STRUCTURE with matching {@code module_id}, or player request-text
 * {@code module:}/{@code typology:}/{@code preset:} opt-in, skips the gap.
 */
final class LandmarkMisrouteRepair {
    private LandmarkMisrouteRepair() {}

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) return plan;
        String landmark = detectLandmark(plan);
        if (landmark == null) return plan;
        if (alreadyLandmarkRouted(plan, landmark)) {
            return plan;
        }
        // Player request-text opt-in without a STRUCTURE executor still gaps when misrouted —
        // tags must not pretend the fixed landmark module already runs.
        if (explicitLandmarkOptIn(plan, landmark) && !looksMisrouted(plan)) {
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
                        "Or opt in with module_id / typology: / preset: for " + landmark
                                + " and route to STRUCTURE",
                        "Re-plan landmark geometry without office/atrium rings")));
    }

    /** True only when a STRUCTURE executor already carries the landmark module_id. */
    private static boolean alreadyLandmarkRouted(LlmPlan plan, String landmark) {
        for (Component c : plan.components()) {
            if (c == null || c.params() == null) continue;
            if (!"STRUCTURE".equalsIgnoreCase(c.componentType())) continue;
            Object module = c.params().get("module_id");
            if (module != null && landmark.equalsIgnoreCase(String.valueOf(module))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Explicit player opt-in from request text only — never AI-forged component params
     * or landmark: feature tags (those are semantic labels, not executor selection).
     */
    private static boolean explicitLandmarkOptIn(LlmPlan plan, String landmark) {
        String blob = requestTextBlob(plan);
        String lower = landmark.toLowerCase(Locale.ROOT);
        return blob.contains("module:" + lower)
                || blob.contains("module_id=" + lower)
                || blob.contains("typology:" + lower)
                || blob.contains("preset:" + lower)
                || blob.contains("preset:<" + lower);
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
        String blob = detectionBlob(plan);
        if (blob.contains("eiffel") || blob.contains("埃菲尔") || blob.contains("艾菲尔")) {
            return "eiffel_tower";
        }
        return null;
    }

    /** Request text + distinguishing features + component ids for landmark name detection. */
    private static String detectionBlob(LlmPlan plan) {
        StringBuilder sb = new StringBuilder(requestTextBlob(plan));
        if (plan.distinguishingFeatures() != null) {
            for (String f : plan.distinguishingFeatures()) {
                if (f != null) sb.append(f).append(' ');
            }
        }
        if (plan.playerFidelityNoticeZh() != null) {
            sb.append(plan.playerFidelityNoticeZh()).append(' ');
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
                    for (String key : List.of("component_id", "module_id", "landmark", "reference_landmark")) {
                        Object v = c.params().get(key);
                        if (v != null) sb.append(v).append(' ');
                    }
                }
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /** Player/server request text only — not component params. */
    private static String requestTextBlob(LlmPlan plan) {
        StringBuilder sb = new StringBuilder();
        if (plan.planMeta() != null) {
            Object text = plan.planMeta().get("request_intent_text");
            if (text != null) sb.append(text).append(' ');
            Object ri = plan.planMeta().get("request_intent");
            if (ri instanceof Map<?, ?> map && map.get("original_text") != null) {
                sb.append(map.get("original_text")).append(' ');
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
