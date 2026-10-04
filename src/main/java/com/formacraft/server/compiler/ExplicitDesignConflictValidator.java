package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.*;
import com.formacraft.common.style.ExplicitDesignPolicy;
import java.util.*;

/** Diagnose conflicting hosted components rather than silently overriding explicit opt-outs. */
final class ExplicitDesignConflictValidator {
    static Optional<CapabilityGap> check(LlmPlan plan, List<Component> components) {
        var bodies = new HashMap<Object, Component>();
        for (var c : components) if (c != null && "MASS_MAIN".equals(c.componentType()) && c.params() != null)
            bodies.put(c.params().get("component_id"), c);
        for (var c : components) {
            if (c == null || c.params() == null) continue;
            var body = bodies.get(c.params().get("host_id"));
            String disabled = null;
            if (body != null) {
                if (c.componentType().startsWith("ROOF") && ExplicitDesignPolicy.roofDisabled(body)
                        && !ExplicitDesignPolicy.roofDisabled(c)) disabled = "roof_type=none";
                if ("FACADE_WINDOWS".equals(c.componentType()) && ExplicitDesignPolicy.windowsDisabled(body)
                        && !ExplicitDesignPolicy.windowsDisabled(c)) disabled = "windows";
                if ("ENTRANCE".equals(c.componentType()) && ExplicitDesignPolicy.entranceDisabled(body)
                        && !ExplicitDesignPolicy.entranceDisabled(c)) disabled = "entrance_type=none";
            }
            if (Set.of("CROWN", "CUPOLA", "DOME").contains(c.componentType())
                    && (ExplicitDesignPolicy.noComplexDecor(plan, c.params()) || body != null
                        && (ExplicitDesignPolicy.noComplexDecor(plan, body.params())
                            || ExplicitDesignPolicy.none(body.params(), "crown_type", "crownType", "crown_template", "crownTemplate"))))
                disabled = "crown / complex decoration";
            if (disabled != null) return Optional.of(new CapabilityGap("E_EXPLICIT_DESIGN_CONFLICT",
                    "构件与明确禁用要求冲突：" + c.componentType() + ", host=" + c.params().get("host_id")
                            + ", disabled=" + disabled, "components[]",
                    List.of("Remove the conflicting component or revise the explicit opt-out.")));
        }
        return Optional.empty();
    }
}
