package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Detects incomplete compositional 四合院 plans. Never silently replaces AI multi-mass
 * compositions with a fixed {@code courtyard_compound} STRUCTURE — that is DESIGN_OVERRIDE.
 * Only an already-routed STRUCTURE {@code courtyard_compound} executor is left alone;
 * request-text {@code typology:}/{@code preset:} alone does not skip the gap.
 */
final class SiheyuanCourtyardMisrouteRepair {
    private SiheyuanCourtyardMisrouteRepair() {}

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) return plan;
        if (!looksSiheyuan(plan)) return plan;
        // Only a STRUCTURE courtyard_compound executor counts as already routed.
        // Request-text typology:/preset: alone is not proof the executor runs.
        if (alreadyCourtyardCompound(plan)) return plan;
        if (!looksMisroutedComposition(plan)) return plan;

        FormacraftMod.LOGGER.info(
                "SiheyuanCourtyardMisrouteRepair: refusing silent courtyard_compound rewrite ({} components)",
                plan.components().size());
        String hint = explicitCourtyardOptIn(plan)
                ? "typology/preset opt-in is present but no STRUCTURE courtyard_compound executor — route or re-plan"
                : "Add COURTYARD_SPACE / PATH and role labels on masses";
        return PlanComponentRewrite.withCapabilityGap(plan, new CapabilityGap(
                "E_SIHEYUAN_COMPOSITION_UNSUPPORTED",
                "四合院组合方案缺少庭院围合或角色标注，已拒绝用内置 courtyard_compound 模板覆盖 AI 构件；请补全正房/厢房/倒座/庭院或路由到 STRUCTURE typology:courtyard_compound",
                "SiheyuanCourtyardMisrouteRepair",
                List.of(
                        hint,
                        "Or ensure STRUCTURE with typology_id=courtyard_compound",
                        "Re-plan the courtyard composition")));
    }

    private static boolean looksSiheyuan(LlmPlan plan) {
        String blob = blob(plan);
        if (blob.contains("四合院") || blob.contains("siheyuan") || blob.contains("合院")) return true;
        if (blob.contains("正房") && (blob.contains("厢房") || blob.contains("倒座"))) return true;
        String style = plan.styleProfile() == null ? "" : plan.styleProfile().toLowerCase(Locale.ROOT);
        return style.contains("siheyuan");
    }

    /**
     * Player request-text typology/preset cue (hint for gap message only).
     * Does not short-circuit misroute detection — executor must be STRUCTURE.
     */
    private static boolean explicitCourtyardOptIn(LlmPlan plan) {
        StringBuilder sb = new StringBuilder();
        if (plan.planMeta() != null) {
            Object text = plan.planMeta().get("request_intent_text");
            if (text != null) sb.append(text).append(' ');
            Object ri = plan.planMeta().get("request_intent");
            if (ri instanceof Map<?, ?> map && map.get("original_text") != null) {
                sb.append(map.get("original_text")).append(' ');
            }
        }
        String blob = sb.toString().toLowerCase(Locale.ROOT);
        return blob.contains("typology:courtyard_compound")
                || blob.contains("preset:courtyard_compound")
                || blob.contains("typology_id=courtyard_compound");
    }

    private static boolean alreadyCourtyardCompound(LlmPlan plan) {
        for (Component c : plan.components()) {
            if (c == null) continue;
            if (!"STRUCTURE".equalsIgnoreCase(c.componentType())) continue;
            if (c.params() != null) {
                for (String key : List.of("typology_id", "typology", "structural_typology")) {
                    Object v = c.params().get(key);
                    if (v != null && String.valueOf(v).toLowerCase(Locale.ROOT).contains("courtyard_compound")) {
                        return true;
                    }
                }
            }
            if (c.features() != null) {
                for (String f : c.features()) {
                    if (f != null && f.toLowerCase(Locale.ROOT).contains("courtyard_compound")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Compositional plans that lack a clear courtyard enclosure, or that scatter many
     * masses without COURTYARD_SPACE / gate / wing roles.
     */
    private static boolean looksMisroutedComposition(LlmPlan plan) {
        int masses = 0;
        boolean hasCourt = false;
        boolean hasWing = false;
        boolean hasGate = false;
        boolean hasMain = false;
        for (Component c : plan.components()) {
            if (c == null || c.componentType() == null) continue;
            String t = c.componentType().trim().toUpperCase(Locale.ROOT);
            if (t.startsWith("MASS") || "TOWER".equals(t) || "GATE".equals(t)) {
                masses++;
                String id = idOf(c).toLowerCase(Locale.ROOT);
                String feat = featuresOf(c).toLowerCase(Locale.ROOT);
                if (id.contains("wing") || id.contains("厢") || feat.contains("wing") || feat.contains("厢")) {
                    hasWing = true;
                }
                if (id.contains("gate") || id.contains("倒座") || id.contains("门楼")
                        || feat.contains("gate") || feat.contains("倒座") || "GATE".equals(t)) {
                    hasGate = true;
                }
                if (id.contains("main") || id.contains("正房") || feat.contains("main_hall")
                        || feat.contains("正房") || "MASS_MAIN".equals(t)) {
                    hasMain = true;
                }
            }
            if (t.contains("COURTYARD") || t.equals("PATH")) {
                hasCourt = true;
            }
        }
        String blob = blob(plan);
        // 三进 (and other multi-jin) layouts are not supported by courtyard_compound's single yard.
        if (blob.contains("三进") || blob.contains("两进") || blob.contains("多进")) {
            return false;
        }
        if (masses >= 4 && !(hasCourt && hasWing && hasGate && hasMain)) return true;
        return masses >= 3 && !hasCourt;
    }

    private static String idOf(Component c) {
        if (c.params() == null) return "";
        Object id = c.params().get("component_id");
        return id == null ? "" : String.valueOf(id);
    }

    private static String featuresOf(Component c) {
        if (c.features() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String f : c.features()) {
            if (f != null) sb.append(f).append(' ');
        }
        return sb.toString();
    }

    private static String blob(LlmPlan plan) {
        StringBuilder sb = new StringBuilder();
        if (plan.styleProfile() != null) sb.append(plan.styleProfile()).append(' ');
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
        if (plan.proportionHints() != null) {
            Object intent = plan.proportionHints().get("architecture_intent");
            if (intent instanceof Map<?, ?> map && map.get("original_text") != null) {
                sb.append(map.get("original_text")).append(' ');
            }
        }
        for (Component c : plan.components()) {
            if (c == null) continue;
            sb.append(featuresOf(c)).append(' ').append(idOf(c)).append(' ');
            if (c.params() != null) {
                for (String key : List.of("typology_id", "typology", "structural_typology", "preset_id")) {
                    Object v = c.params().get(key);
                    if (v != null) sb.append(v).append(' ');
                }
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
