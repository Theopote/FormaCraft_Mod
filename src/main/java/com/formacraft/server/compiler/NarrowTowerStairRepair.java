package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.component.util.ComponentParamParsers;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.server.generation.component.impl.StraightStairComponentGenerator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * In thin towers / spires, width-3 stairs clog the shaft. Force width=1 when the host
 * interior clear span is small, and harden the shell so walls stay continuous.
 */
final class NarrowTowerStairRepair {
    private NarrowTowerStairRepair() {}

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) return plan;
        Map<String, Component> hosts = new HashMap<>();
        for (Component c : plan.components()) {
            if (c == null || c.params() == null || c.dimensions() == null) continue;
            Object id = c.params().get("component_id");
            if (id == null) continue;
            String type = c.componentType() == null ? "" : c.componentType().toUpperCase(Locale.ROOT);
            if (ComponentPlanTypes.isMassType(type) || "TOWER".equals(type) || type.startsWith("TOWER_")) {
                hosts.put(String.valueOf(id), c);
            }
        }
        List<Component> out = new ArrayList<>(plan.components().size());
        boolean changed = false;
        for (Component c : plan.components()) {
            Component next = repairHostShell(c);
            if (next != c) changed = true;
            if (StraightStairComponentGenerator.accepts(next)) {
                Component stair = clampStairWidth(next, hosts);
                if (stair != next) {
                    next = stair;
                    changed = true;
                }
            }
            out.add(next);
        }
        return changed ? PlanComponentRewrite.withComponents(plan, out) : plan;
    }

    private static Component repairHostShell(Component host) {
        if (host == null || host.params() == null || host.dimensions() == null) return host;
        String type = host.componentType() == null ? "" : host.componentType().toUpperCase(Locale.ROOT);
        String id = host.params().get("component_id") == null ? ""
                : String.valueOf(host.params().get("component_id")).toLowerCase(Locale.ROOT);
        boolean spireLike = "TOWER".equals(type) || type.startsWith("TOWER_")
                || id.contains("spire") || id.contains("尖塔") || id.contains("belfry")
                || id.contains("campanile") || looksSpireFeatures(host.features());
        int span = Math.min(host.dimensions().width(), host.dimensions().depth());
        if (!spireLike && !(span <= 9 && host.dimensions().height() >= span * 2)) return host;
        Map<String, Object> params = new HashMap<>(host.params());
        boolean changed = false;
        if (!Boolean.TRUE.equals(params.get("hollow"))) {
            params.put("hollow", true);
            changed = true;
        }
        int wall = ComponentParamParsers.intParam(params, 0, "wall_thickness", "wallThickness");
        if (wall <= 0) {
            params.put("wall_thickness", 1);
            changed = true;
        }
        // Narrow shafts read incomplete when dense window punch removes the shell.
        if (span <= 7 && !Boolean.TRUE.equals(params.get("suppress_windows"))) {
            params.put("suppress_windows", true);
            params.putIfAbsent("window_style", "none");
            changed = true;
        }
        if (!changed) return host;
        FormacraftMod.LOGGER.info(
                "NarrowTowerStairRepair: harden spire shell id={} span={}", id, span);
        return new Component(host.componentType(), host.slotId(), host.relativePosition(),
                host.dimensions(), host.features(), params);
    }

    private static Component clampStairWidth(Component stair, Map<String, Component> hosts) {
        if (stair.params() == null) return stair;
        Object hostId = stair.params().get("host_id");
        Component host = hostId == null ? null : hosts.get(String.valueOf(hostId));
        int width = ComponentParamParsers.intParam(stair.params(), 3, "width");
        int maxWidth = 3;
        if (host != null && host.dimensions() != null) {
            int wall = Math.max(1, ComponentParamParsers.intParam(host.params(), 1,
                    "wall_thickness", "wallThickness"));
            int clear = Math.min(host.dimensions().width(), host.dimensions().depth()) - 2 * wall;
            if (clear <= 5 || Math.min(host.dimensions().width(), host.dimensions().depth()) <= 9) {
                maxWidth = 1;
            } else if (clear <= 7) {
                maxWidth = 2;
            }
        } else if (looksSpireFeatures(stair.features())) {
            maxWidth = 1;
        }
        if (width <= maxWidth) return stair;
        Map<String, Object> params = new HashMap<>(stair.params());
        params.put("width", maxWidth);
        FormacraftMod.LOGGER.info(
                "NarrowTowerStairRepair: stair width {}→{} host={}", width, maxWidth, hostId);
        return new Component(stair.componentType(), stair.slotId(), stair.relativePosition(),
                stair.dimensions(), stair.features(), params);
    }

    private static boolean looksSpireFeatures(List<String> features) {
        if (features == null) return false;
        for (String f : features) {
            if (f == null) continue;
            String lower = f.toLowerCase(Locale.ROOT);
            if (lower.contains("spire") || lower.contains("尖塔") || lower.contains("belfry")
                    || lower.contains("tower stair") || lower.contains("市政厅")) {
                return true;
            }
        }
        return false;
    }
}
