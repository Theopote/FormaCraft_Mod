package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * When the user asks for a non-suspension bridge (石拱/木桥/廊桥/梁桥…) but the plan was forced onto
 * {@code typology:suspension_bridge} / ASSEMBLY suspension preset, strip that hard route so
 * compositional MASS / deck / pier plans can generate instead of the same twin-tower silhouette.
 */
final class BridgeMisrouteRepair {
    private BridgeMisrouteRepair() {}

    private static final List<String> SUSPENSION_CUES = List.of(
            "悬索", "吊桥", "斜拉", "主缆", "桥塔", "金门",
            "suspension", "cable-stayed", "cable stayed", "golden gate");

    private static final List<String> NON_SUSPENSION_CUES = List.of(
            "石拱", "拱桥", "石桥", "木桥", "廊桥", "梁桥", "拱券", "板桥",
            "arch bridge", "stone arch", "stone bridge", "wooden bridge",
            "beam bridge", "covered bridge", "footbridge", "人行桥");

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) {
            return plan;
        }
        String corpus = intentCorpus(plan);
        if (corpus.isBlank()) {
            return plan;
        }
        // Keep suspension / Golden Gate plans alone.
        if (containsAny(corpus, SUSPENSION_CUES)) {
            return plan;
        }
        boolean bridgeIntent = corpus.contains("桥") || corpus.contains("bridge")
                || containsAny(corpus, NON_SUSPENSION_CUES);
        if (!bridgeIntent) {
            return plan;
        }
        List<Component> out = new ArrayList<>(plan.components().size());
        boolean changed = false;
        for (Component c : plan.components()) {
            Component repaired = stripForcedSuspension(c);
            if (repaired != c) {
                changed = true;
            }
            out.add(repaired);
        }
        if (!changed) {
            return plan;
        }
        FormacraftMod.LOGGER.info(
                "BridgeMisrouteRepair: stripped forced suspension_bridge typology for non-suspension bridge intent");
        return PlanComponentRewrite.withComponents(plan, out);
    }

    private static Component stripForcedSuspension(Component c) {
        if (c == null) {
            return c;
        }
        boolean hit = false;
        Map<String, Object> params = c.params() == null ? null : new HashMap<>(c.params());
        List<String> features = c.features() == null ? null : new ArrayList<>(c.features());
        if (params != null) {
            for (String key : List.of(
                    "typology_id", "structural_typology", "typologyId", "structuralTypologyId", "typology")) {
                Object v = params.get(key);
                if (v != null && String.valueOf(v).toLowerCase(Locale.ROOT).contains("suspension")) {
                    params.remove(key);
                    hit = true;
                }
            }
            Object assembly = params.get("assembly");
            if (assembly instanceof Map<?, ?> raw) {
                @SuppressWarnings("unchecked")
                Map<String, Object> asm = new HashMap<>((Map<String, Object>) raw);
                Object preset = asm.get("preset");
                if (preset == null) {
                    preset = asm.get("presetId");
                }
                if (preset != null && String.valueOf(preset).toLowerCase(Locale.ROOT)
                        .contains("suspension_bridge")) {
                    asm.remove("preset");
                    asm.remove("presetId");
                    params.put("assembly", asm);
                    hit = true;
                }
            }
            Object module = params.get("module_id");
            if (module != null && String.valueOf(module).toLowerCase(Locale.ROOT)
                    .contains("golden_gate")) {
                params.remove("module_id");
                params.remove("landmark");
                params.remove("reference_landmark");
                hit = true;
            }
        }
        if (features != null) {
            List<String> next = new ArrayList<>();
            for (String f : features) {
                if (f == null) continue;
                String lower = f.toLowerCase(Locale.ROOT);
                if (lower.contains("typology:suspension")
                        || lower.contains("landmark:golden_gate")
                        || lower.contains("module:golden_gate")) {
                    hit = true;
                    continue;
                }
                next.add(f);
            }
            if (hit) {
                features = next;
            }
        }
        if (!hit) {
            return c;
        }
        // STRUCTURE without typology is a dead carrier — demote to MASS so pier/deck composition works.
        String type = c.componentType();
        if (type != null && "STRUCTURE".equalsIgnoreCase(type.trim())) {
            type = "MASS_MAIN";
            if (params == null) {
                params = new HashMap<>();
            }
            params.putIfAbsent("open", true);
            params.putIfAbsent("wall_type", "none");
            params.putIfAbsent("suppress_windows", true);
            params.putIfAbsent("bridge_misroute_demoted", true);
        }
        return new Component(
                type, c.slotId(), c.relativePosition(), c.dimensions(), features, params);
    }

    private static String intentCorpus(LlmPlan plan) {
        StringBuilder sb = new StringBuilder();
        if (plan.planMeta() != null) {
            Object ri = plan.planMeta().get("request_intent_text");
            if (ri != null) sb.append(ri).append(' ');
            Object intent = plan.planMeta().get("request_intent");
            if (intent instanceof Map<?, ?> m && m.get("original_text") != null) {
                sb.append(m.get("original_text")).append(' ');
            }
        }
        if (plan.playerFidelityNoticeZh() != null) {
            sb.append(plan.playerFidelityNoticeZh()).append(' ');
        }
        if (plan.distinguishingFeatures() != null) {
            for (String f : plan.distinguishingFeatures()) {
                if (f != null) sb.append(f).append(' ');
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
