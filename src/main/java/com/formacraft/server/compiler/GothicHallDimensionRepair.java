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
 * {@code gothic_cathedral_hall} STRUCTURE often keeps typology defaults (25×45) while the
 * component envelope / user contract is a smaller hall (e.g. 12×20). Sync width/depth to the
 * authored envelope and drop unsolicited apse terminals that punch out the rear end wall.
 */
final class GothicHallDimensionRepair {
    private GothicHallDimensionRepair() {}

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) {
            return plan;
        }
        String corpus = intentCorpus(plan);
        List<Component> out = new ArrayList<>(plan.components().size());
        boolean changed = false;
        for (Component c : plan.components()) {
            Component next = repair(c, plan, corpus);
            if (next != c) {
                changed = true;
            }
            out.add(next);
        }
        if (!changed) {
            return plan;
        }
        return PlanComponentRewrite.withComponents(plan, out);
    }

    private static Component repair(Component c, LlmPlan plan, String corpus) {
        if (c == null || c.params() == null || c.dimensions() == null) {
            return c;
        }
        if (!isGothicHall(c)) {
            return c;
        }
        Map<String, Object> params = new HashMap<>(c.params());
        boolean changed = false;

        int envW = Math.max(5, c.dimensions().width());
        int envD = Math.max(5, c.dimensions().depth());
        int fromHintsW = measuredInt(plan, "width");
        int fromHintsD = measuredInt(plan, "depth");
        int targetW = firstPositive(fromHintsW, envW);
        int targetD = firstPositive(fromHintsD, envD);
        // Keep odd spans for symmetric nave / twin-tower math.
        targetW = targetW % 2 == 0 ? targetW - 1 : targetW;
        targetD = targetD % 2 == 0 ? targetD - 1 : targetD;
        targetW = Math.max(9, targetW);
        targetD = Math.max(11, targetD);

        int paramW = intParam(params, "width", targetW);
        int paramD = intParam(params, "depth", targetD);
        if (paramW != targetW || paramD != targetD) {
            params.put("width", targetW);
            params.put("depth", targetD);
            changed = true;
        }

        // Unsolicited apse clears the rear rows and leaves a broken end wall on compact halls.
        boolean wantsApse = containsAny(corpus, List.of("后殿", "半圆殿", "apse", "choir", "唱诗班", "圣坛"));
        if (!wantsApse && (Boolean.TRUE.equals(params.get("includeApse"))
                || Boolean.TRUE.equals(params.get("includeChoir")))) {
            params.put("includeApse", false);
            params.put("includeChoir", false);
            changed = true;
        }
        // Compact footprints cannot host twin towers without eating the nave — honour includeTowers=false.
        if (targetW < 17 && !"none".equalsIgnoreCase(String.valueOf(params.getOrDefault("towerArrangement", "twin")))) {
            Object includeTowers = params.get("includeTowers");
            if (Boolean.FALSE.equals(includeTowers)
                    || "false".equalsIgnoreCase(String.valueOf(includeTowers))) {
                params.put("towerArrangement", "none");
                changed = true;
            }
        }

        if (!changed) {
            return c;
        }
        FormacraftMod.LOGGER.info(
                "GothicHallDimensionRepair: sync gothic hall {}x{} → {}x{} (apse={})",
                paramW, paramD, targetW, targetD, params.get("includeApse"));
        return new Component(c.componentType(), c.slotId(), c.relativePosition(),
                new com.formacraft.common.llm.dto.Dimensions(targetW, targetD, c.dimensions().height()),
                c.features(), params);
    }

    private static boolean isGothicHall(Component c) {
        if (c.params() != null) {
            for (String key : List.of("typology_id", "typology", "structural_typology")) {
                Object v = c.params().get(key);
                if (v != null && String.valueOf(v).toLowerCase(Locale.ROOT).contains("gothic_cathedral_hall")) {
                    return true;
                }
            }
        }
        if (c.features() != null) {
            for (String f : c.features()) {
                if (f != null && f.toLowerCase(Locale.ROOT).contains("gothic_cathedral_hall")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int measuredInt(LlmPlan plan, String key) {
        if (plan.proportionHints() == null) return 0;
        Object measured = plan.proportionHints().get("measured");
        if (!(measured instanceof Map<?, ?> map)) return 0;
        Object v = map.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v != null) {
            try {
                return Integer.parseInt(String.valueOf(v).trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static int firstPositive(int... values) {
        for (int v : values) {
            if (v > 0) return v;
        }
        return 0;
    }

    private static int intParam(Map<String, Object> params, String key, int fallback) {
        Object v = params.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v != null) {
            try {
                return Integer.parseInt(String.valueOf(v).trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
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
        if (plan.playerFidelityNoticeZh() != null) sb.append(plan.playerFidelityNoticeZh()).append(' ');
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
