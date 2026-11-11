package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.component.util.ComponentParamParsers;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.server.generation.component.impl.StraightStairComponentGenerator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * In thin towers / spires, width-3 stairs clog the shaft. Force width=1 when the host
 * interior clear span is small, and harden the shell so walls stay continuous.
 * <p>
 * Window suppression is skipped when the plan already authors an explicit
 * {@code FACADE_WINDOWS} bound to that host — otherwise Confirm/compile drops the
 * facade via {@link ExplicitDesignConflictRepair} and reports {@code E_DESIGN_INCOMPLETE}.
 */
final class NarrowTowerStairRepair {
    private NarrowTowerStairRepair() {}

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) return plan;
        Map<String, Component> hosts = new HashMap<>();
        Set<String> hostsWithExplicitFacade = new HashSet<>();
        for (Component c : plan.components()) {
            if (c == null || c.params() == null) continue;
            String type = c.componentType() == null ? "" : c.componentType().toUpperCase(Locale.ROOT);
            if (c.dimensions() != null) {
                Object id = c.params().get("component_id");
                if (id != null && (ComponentPlanTypes.isMassType(type)
                        || "TOWER".equals(type) || type.startsWith("TOWER_"))) {
                    hosts.put(String.valueOf(id), c);
                }
            }
            if ("FACADE_WINDOWS".equals(type) || "FACADE_OPENING".equals(type)
                    || "WINDOW".equals(type) || "WINDOWS".equals(type)) {
                Object hostId = c.params().get("host_id");
                if (hostId != null) {
                    hostsWithExplicitFacade.add(String.valueOf(hostId));
                }
            }
        }
        List<Component> out = new ArrayList<>(plan.components().size());
        boolean changed = false;
        for (Component c : plan.components()) {
            Component next = repairHostShell(c, hostsWithExplicitFacade);
            if (next != c) changed = true;
            // Exterior approach flights keep authored width (stilts shore stairs, etc.).
            // Narrow-tower clamp is for interior shafts only.
            if (StraightStairComponentGenerator.accepts(next)
                    && (next.params() == null || !Boolean.TRUE.equals(next.params().get("exterior")))) {
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

    private static Component repairHostShell(Component host, Set<String> hostsWithExplicitFacade) {
        if (host == null || host.params() == null || host.dimensions() == null) return host;
        String type = host.componentType() == null ? "" : host.componentType().toUpperCase(Locale.ROOT);
        // Only harden structural hosts. Thin FACADE_WINDOWS / ENTRANCE strips (depth=1)
        // otherwise match the narrow-span heuristic and inherit window_style=none.
        if (!(ComponentPlanTypes.isMassType(type) || "TOWER".equals(type) || type.startsWith("TOWER_"))) {
            return host;
        }
        // Bridge / terrace plates are thin by design — not spire shafts.
        if ("plate".equalsIgnoreCase(String.valueOf(host.params().get("extrude_mode")))) {
            return host;
        }
        Object plateRole = host.params().get("plate_role");
        if (plateRole == null) plateRole = host.params().get("plateRole");
        if (plateRole != null) {
            String role = String.valueOf(plateRole).toLowerCase(Locale.ROOT);
            if (role.contains("bridge") || role.contains("terrace") || role.contains("deck")) {
                return host;
            }
        }
        String idRaw = host.params().get("component_id") == null ? ""
                : String.valueOf(host.params().get("component_id"));
        String id = idRaw.toLowerCase(Locale.ROOT);
        if (id.contains("bridge") || id.contains("deck") || id.contains("span")) {
            return host;
        }
        // Short oculus / roof lanterns are crowns, not climbable spire shafts.
        if (id.contains("lantern") || id.contains("oculus") || id.contains("采光")
                || id.contains("cupola") || id.contains("light_well")
                || (host.dimensions().height() <= 4 && (id.contains("hex") || looksLanternFeatures(host.features())))) {
            return host;
        }
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
        // Narrow shafts read incomplete when dense window punch removes the shell —
        // but do not override an authored facade satellite for this host.
        boolean keepAuthoredFacade = !idRaw.isBlank() && hostsWithExplicitFacade.contains(idRaw);
        if (span <= 7 && !Boolean.TRUE.equals(params.get("suppress_windows")) && !keepAuthoredFacade) {
            params.put("suppress_windows", true);
            params.putIfAbsent("window_style", "none");
            changed = true;
        }
        if (!changed) return host;
        FormacraftMod.LOGGER.info(
                "NarrowTowerStairRepair: harden spire shell id={} span={} keepFacade={}",
                id, span, keepAuthoredFacade);
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
            String hostType = host.componentType() == null ? "" : host.componentType().toUpperCase(Locale.ROOT);
            boolean spireHost = "TOWER".equals(hostType) || hostType.startsWith("TOWER_")
                    || looksSpireFeatures(host.features());
            // Ordinary houses (clear 6–8) keep width-2/3; only tight shafts / spires force width-1.
            if (clear <= 5 || spireHost) {
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

    private static boolean looksLanternFeatures(List<String> features) {
        if (features == null) return false;
        for (String f : features) {
            if (f == null) continue;
            String lower = f.toLowerCase(Locale.ROOT);
            if (lower.contains("lantern") || lower.contains("oculus") || lower.contains("采光")
                    || lower.contains("cupola") || lower.contains("light_well")
                    || lower.contains("hexagonal_lantern")) {
                return true;
            }
        }
        return false;
    }
}
