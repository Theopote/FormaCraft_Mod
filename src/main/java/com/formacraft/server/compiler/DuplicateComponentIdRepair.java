package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.component.util.MassComponentKinds;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Uniquify duplicate hostable {@code params.component_id} values before
 * {@link HostedPlateDesignValidator} hard-fails with {@code E_DUPLICATE_COMPONENT_ID}.
 */
final class DuplicateComponentIdRepair {
    private DuplicateComponentIdRepair() {}

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) {
            return plan;
        }
        Set<String> seen = new HashSet<>();
        List<Component> out = new ArrayList<>(plan.components().size());
        boolean changed = false;
        int dupIndex = 0;
        for (Component c : plan.components()) {
            if (c == null || c.params() == null || !isHostableMass(c)) {
                out.add(c);
                continue;
            }
            Object raw = c.params().get("component_id");
            if (raw == null) {
                out.add(c);
                continue;
            }
            String id = String.valueOf(raw).trim();
            if (id.isEmpty() || seen.add(id)) {
                out.add(c);
                continue;
            }
            String unique;
            do {
                dupIndex++;
                unique = id + "_" + dupIndex;
            } while (seen.contains(unique));
            seen.add(unique);
            Map<String, Object> params = new HashMap<>(c.params());
            params.put("component_id", unique);
            params.put("component_id_mapped_from", id);
            FormacraftMod.LOGGER.info(
                    "DuplicateComponentIdRepair: component_id {} → {}", id, unique);
            out.add(new Component(
                    c.componentType(), c.slotId(), c.relativePosition(),
                    c.dimensions(), c.features(), params));
            changed = true;
        }
        return changed ? PlanComponentRewrite.withComponents(plan, out) : plan;
    }

    private static boolean isHostableMass(Component c) {
        if (c == null || c.params() == null) return false;
        if ("plate".equalsIgnoreCase(String.valueOf(c.params().get("extrude_mode")))) {
            return false;
        }
        return MassComponentKinds.isHostMass(c);
    }
}
