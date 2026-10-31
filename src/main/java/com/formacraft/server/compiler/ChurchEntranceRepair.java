package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.component.util.ComponentParamParsers;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Church / cathedral entrances often arrive as tiny 1×2 holes. Prefer a taller arched portal.
 */
final class ChurchEntranceRepair {
    private ChurchEntranceRepair() {}

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) return plan;
        if (!looksChurch(plan)) return plan;
        List<Component> out = new ArrayList<>(plan.components().size());
        boolean changed = false;
        for (Component c : plan.components()) {
            Component repaired = repair(c);
            if (repaired != c) changed = true;
            out.add(repaired);
        }
        return changed ? PlanComponentRewrite.withComponents(plan, out) : plan;
    }

    private static Component repair(Component c) {
        if (c == null || !"ENTRANCE".equalsIgnoreCase(c.componentType()) || c.params() == null) {
            return c;
        }
        Map<String, Object> params = new HashMap<>(c.params());
        boolean changed = false;
        Object style = params.get("door_style");
        if (style == null) style = params.get("doorStyle");
        String styleText = style == null ? "" : String.valueOf(style).toLowerCase(Locale.ROOT);
        if (!styleText.contains("arch")) {
            params.put("door_style", "arched");
            changed = true;
        }
        List<String> features = c.features() == null ? new ArrayList<>() : new ArrayList<>(c.features());
        if (features.stream().noneMatch(f -> f != null && f.toLowerCase(Locale.ROOT).contains("arch"))) {
            features.add("arched");
            changed = true;
        }
        int doorW = ComponentParamParsers.intParam(params, 0, "door_width", "doorWidth");
        int doorH = ComponentParamParsers.intParam(params, 0, "door_height", "doorHeight");
        if (doorW < 3) {
            params.put("door_width", 3);
            changed = true;
        }
        if (doorH < 4) {
            params.put("door_height", 4);
            changed = true;
        }
        Dimensions dims = c.dimensions();
        if (dims != null) {
            int w = Math.max(dims.width(), 5);
            int h = Math.max(dims.height(), 5);
            int d = Math.max(dims.depth(), 1);
            if (w != dims.width() || h != dims.height() || d != dims.depth()) {
                dims = new Dimensions(w, d, h);
                changed = true;
            }
        }
        if (!changed) return c;
        FormacraftMod.LOGGER.info("ChurchEntranceRepair: enlarge arched portal id={}",
                params.get("component_id"));
        return new Component(c.componentType(), c.slotId(), c.relativePosition(),
                dims, features, params);
    }

    private static boolean looksChurch(LlmPlan plan) {
        if (plan.distinguishingFeatures() != null) {
            for (String f : plan.distinguishingFeatures()) {
                if (f != null && looksChurchToken(f)) return true;
            }
        }
        if (plan.playerFidelityNoticeZh() != null && looksChurchToken(plan.playerFidelityNoticeZh())) {
            return true;
        }
        if (plan.styleProfile() != null && looksChurchToken(plan.styleProfile())) return true;
        for (Component c : plan.components()) {
            if (c == null) continue;
            if (c.params() != null && c.params().get("component_id") != null
                    && looksChurchToken(String.valueOf(c.params().get("component_id")))) {
                return true;
            }
            if (c.features() != null) {
                for (String f : c.features()) {
                    if (f != null && looksChurchToken(f)) return true;
                }
            }
        }
        return false;
    }

    private static boolean looksChurchToken(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        return lower.contains("church") || lower.contains("cathedral") || lower.contains("chapel")
                || lower.contains("教堂") || lower.contains("天主堂") || lower.contains("礼拜");
    }
}
