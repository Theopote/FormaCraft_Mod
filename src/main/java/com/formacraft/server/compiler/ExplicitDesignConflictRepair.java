package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.style.ExplicitDesignPolicy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Drop satellites that contradict an explicit host opt-out (e.g. FACADE_WINDOWS on a
 * windowless bridge deck) instead of failing the whole plan.
 */
final class ExplicitDesignConflictRepair {
    private ExplicitDesignConflictRepair() {}

    static List<Component> apply(List<Component> components) {
        if (components == null || components.isEmpty()) return components;
        var bodies = new HashMap<Object, Component>();
        for (var c : components) {
            if (c != null && "MASS_MAIN".equals(c.componentType()) && c.params() != null) {
                bodies.put(c.params().get("component_id"), c);
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
            out.add(c);
        }
        return changed ? out : components;
    }
}
