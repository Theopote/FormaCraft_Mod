package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 「吊脚楼」prompts must not lock onto {@code suspension_bridge} just because they mention 吊桥.
 * <p>
 * Never silently replace an open AI composition with a fixed stilts shelter template
 * (DESIGN_OVERRIDE). When a STRUCTURE carries the suspension_bridge typology executor
 * under stilt-shelter intent — including residual bridges beside an otherwise valid stilts
 * composition — emit {@code E_STILT_SHELTER_ROUTE_UNSUPPORTED}. Feature tags alone are not
 * an executor.
 */
final class StiltShelterMisrouteRepair {
    private StiltShelterMisrouteRepair() {}

    private static final List<String> STILT_SHELTER_CUES = List.of(
            "吊脚楼", "吊脚", "避难所", "stilt house", "stilted", "pile dwelling", "干栏");

    private static final List<String> TRUE_BRIDGE_CUES = List.of(
            "悬索", "斜拉", "金门", "golden gate", "suspension bridge", "cable-stayed",
            "桥塔", "主缆", "deck span");

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) {
            return plan;
        }
        String corpus = intentCorpus(plan);
        if (!containsAny(corpus, STILT_SHELTER_CUES)) {
            return plan;
        }
        if (containsAny(corpus, TRUE_BRIDGE_CUES)) {
            return plan;
        }
        // Residual suspension_bridge STRUCTURE must gap even when stilts masses already exist —
        // leaving the bridge executor would still emit wrong geometry (DESIGN_OVERRIDE risk).
        Component bridge = findSuspensionBridgeExecutor(plan);
        if (bridge != null) {
            FormacraftMod.LOGGER.info(
                    "StiltShelterMisrouteRepair: refusing silent stilts-shelter rewrite ({} components)",
                    plan.components().size());
            return PlanComponentRewrite.withCapabilityGap(plan, new CapabilityGap(
                    "E_STILT_SHELTER_ROUTE_UNSUPPORTED",
                    "吊脚楼意图命中了 suspension_bridge STRUCTURE 执行器，已拒绝用内置 stilts shelter 模板覆盖 AI 构件；请改用开放组合（MASS + FOUNDATION stilts）或显式 stilt_shelter 路由",
                    "StiltShelterMisrouteRepair",
                    List.of(
                            "Keep authored masses and re-plan stilts foundation / access",
                            "Or route STRUCTURE with typology_id=stilt_shelter when that executor exists",
                            "Do not treat suspension_bridge feature tags as stilts opt-in",
                            "Remove residual suspension_bridge STRUCTURE when stilts composition is already present")));
        }
        return plan;
    }

    /**
     * Only a STRUCTURE with suspension_bridge typology/module params counts as the misrouted
     * executor. Feature tags alone must not trigger the gap (semantic labels ≠ routing).
     */
    private static Component findSuspensionBridgeExecutor(LlmPlan plan) {
        for (Component c : plan.components()) {
            if (c == null || c.params() == null) continue;
            if (!"STRUCTURE".equalsIgnoreCase(c.componentType())) continue;
            for (String key : List.of("typology_id", "typology", "structural_typology",
                    "module_id", "reference_landmark")) {
                Object v = c.params().get(key);
                if (v != null && String.valueOf(v).toLowerCase(Locale.ROOT).contains("suspension_bridge")) {
                    return c;
                }
            }
        }
        return null;
    }

    private static String intentCorpus(LlmPlan plan) {
        StringBuilder sb = new StringBuilder();
        if (plan.playerFidelityNoticeZh() != null) sb.append(plan.playerFidelityNoticeZh()).append(' ');
        if (plan.styleProfile() != null) sb.append(plan.styleProfile()).append(' ');
        if (plan.distinguishingFeatures() != null) {
            for (String f : plan.distinguishingFeatures()) {
                if (f != null) sb.append(f).append(' ');
            }
        }
        if (plan.planMeta() != null) {
            Object text = plan.planMeta().get("request_intent_text");
            if (text != null) sb.append(text).append(' ');
            Object intent = plan.planMeta().get("request_intent");
            if (intent instanceof Map<?, ?> map && map.get("original_text") != null) {
                sb.append(map.get("original_text")).append(' ');
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String corpus, List<String> cues) {
        for (String cue : cues) {
            if (cue != null && !cue.isBlank() && corpus.contains(cue.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
