package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.style.ExplicitDesignPolicy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Drop satellites that contradict an explicit host opt-out (e.g. FACADE_WINDOWS on an
 * open pavilion) instead of failing the whole plan. Runs before authored validation.
 */
final class ExplicitDesignConflictRepair {
    private ExplicitDesignConflictRepair() {}

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) return plan;
        List<Component> repaired = apply(plan.components());
        return repaired != plan.components() ? PlanComponentRewrite.withComponents(plan, repaired) : plan;
    }

    static List<Component> apply(List<Component> components) {
        if (components == null || components.isEmpty()) return components;
        var bodies = new HashMap<Object, Component>();
        var roofHosts = new java.util.HashSet<String>();
        for (var c : components) {
            if (c == null || c.params() == null) continue;
            if ("MASS_MAIN".equals(c.componentType()) || "MASS_ANCILLARY".equals(c.componentType())
                    || "GATE".equals(c.componentType())) {
                Object id = c.params().get("component_id");
                if (id != null) bodies.put(id, c);
            }
            if (ComponentPlanTypes.isRoofType(ComponentPlanTypes.normalizeType(c.componentType()))) {
                Object host = c.params().get("host_id");
                if (host == null) host = c.params().get("hostId");
                if (host != null) roofHosts.add(String.valueOf(host));
            }
        }
        List<Component> out = new ArrayList<>(components.size());
        boolean changed = false;
        for (var c : components) {
            if (c == null || c.params() == null) {
                out.add(c);
                continue;
            }
            var body = bodies.get(c.params().get("host_id"));
            if (body != null
                    && "FACADE_WINDOWS".equals(c.componentType())
                    && ExplicitDesignPolicy.windowsDisabled(body)
                    && !ExplicitDesignPolicy.windowsDisabled(c)) {
                FormacraftMod.LOGGER.info(
                        "ExplicitDesignConflictRepair: drop FACADE_WINDOWS host={} (windows disabled)",
                        c.params().get("host_id"));
                changed = true;
                continue;
            }
            if (body != null
                    && "ENTRANCE".equals(c.componentType())
                    && ExplicitDesignPolicy.entranceDisabled(body)
                    && !ExplicitDesignPolicy.entranceDisabled(c)
                    && !Boolean.TRUE.equals(c.params().get("compiler_internal_portal"))) {
                FormacraftMod.LOGGER.info(
                        "ExplicitDesignConflictRepair: drop ENTRANCE host={} (entrance disabled)",
                        c.params().get("host_id"));
                changed = true;
                continue;
            }
            // Temple/vernacular gates already carry a pitched ROOF; a classical CROWN on the same
            // host overwrites roof tiles → E_PATCH_CONFLICT. Prefer the authored roof.
            String type = ComponentPlanTypes.normalizeType(c.componentType());
            if (("CROWN".equals(type) || "CUPOLA".equals(type) || "DOME".equals(type))) {
                Object host = c.params().get("host_id");
                if (host == null) host = c.params().get("hostId");
                if (host != null && roofHosts.contains(String.valueOf(host))) {
                    FormacraftMod.LOGGER.info(
                            "ExplicitDesignConflictRepair: drop {} host={} (ROOF already present)",
                            type, host);
                    changed = true;
                    continue;
                }
            }
            out.add(c);
        }
        return changed ? out : components;
    }
}
