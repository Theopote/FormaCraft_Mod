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
                // Mass roof_type=none + separate ROOF is the delegated-roof pattern unless the
                // building contract explicitly requires no roof at all.
                if (c.componentType().startsWith("ROOF") && ExplicitDesignPolicy.roofDisabled(body)
                        && !ExplicitDesignPolicy.roofDisabled(c)
                        && contractRequiresRoofNone(plan, body)) disabled = "roof_type=none";
                if ("FACADE_WINDOWS".equals(c.componentType()) && ExplicitDesignPolicy.windowsDisabled(body)
                        && !ExplicitDesignPolicy.windowsDisabled(c)) disabled = "windows";
                if ("ENTRANCE".equals(c.componentType()) && ExplicitDesignPolicy.entranceDisabled(body)
                        && !ExplicitDesignPolicy.entranceDisabled(c)
                        && !declaredInternalOpening(body, c)) disabled = "entrance_type=none";
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

    // Disabling an external entrance does not cancel an explicitly declared internal passage.
    private static boolean declaredInternalOpening(Component body, Component opening) {
        if (!Boolean.TRUE.equals(opening.params().get("compiler_internal_portal"))
                || opening.params().get("target_host_id") == null
                || !(body.params().get("internal_openings") instanceof List<?> declarations)) return false;
        return declarations.stream().anyMatch(raw -> raw instanceof Map<?,?> spec
                && String.valueOf(spec.get("side")).equalsIgnoreCase(String.valueOf(opening.params().get("facing"))));
    }

    private static boolean contractRequiresRoofNone(LlmPlan plan, Component body) {
        if (plan == null || plan.proportionHints() == null || body == null || body.params() == null) return false;
        if (!(plan.proportionHints().get("building_contract") instanceof Map<?, ?> contract)
                || !(contract.get("requirements") instanceof List<?> requirements)) return false;
        Object id = body.params().get("component_id");
        for (Object raw : requirements) {
            if (!(raw instanceof Map<?, ?> req)) continue;
            if (!"roof_type".equals(req.get("property")) || !"none".equals(String.valueOf(req.get("value")))) continue;
            Object scope = req.get("scope");
            if ("all_main_masses".equals(scope) || "plan".equals(scope)) return true;
            if (scope != null && scope.equals(body.params().get("requirement_scope"))) return true;
            if (req.get("target_components") instanceof List<?> targets
                    && (targets.contains(id) || targets.contains(body.params().get("host_id")))) return true;
        }
        return false;
    }
}
