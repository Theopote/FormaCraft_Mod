package com.formacraft.server.dsl;

import com.formacraft.common.dsl.*;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import java.util.*;

/** Explicit opt-in inside LlmPlan, never a second generation/confirmation pipeline. */
public final class FbdPlanLowering {
    public static final String COMPILED = "_server_fbd_compilation";
    public static final String SOURCE = "_server_fbd_source";
    public record SourceContext(String componentPath, String slotId) {}
    private static final Set<String> ALTERNATIVES = Set.of("assembly", "ops", "graph", "components", "macro", "preset", "presetId", "skeleton");
    private FbdPlanLowering() {}

    public static List<Component> lower(List<Component> components) {
        var result = new ArrayList<Component>();
        long totalCost = 0, totalSteps = 0, totalCommands = 0;
        for (int i = 0; i < components.size(); i++) {
            Component c = components.get(i);
            if (c != null && c.params() != null && (c.params().containsKey(COMPILED) || c.params().containsKey(SOURCE))) {
                report(new FbdException("reserved_field", "parse", null, "", "Server compilation metadata cannot be supplied by a plan"),
                        "/components/" + i + "/params/" + (c.params().containsKey(COMPILED) ? COMPILED : SOURCE));
                return List.of();
            }
            if (c == null || c.params() == null || !c.params().containsKey("fbd_program")) {
                result.add(c); continue;
            }
            String path = "/components/" + i + "/params/fbd_program";
            try {
                if (!"ASSEMBLY".equals(c.componentType())) throw new FbdException("invalid_authority", "parse", null, "", "fbd_program requires ASSEMBLY");
                Object authority = c.params().get("generation_authority");
                if (authority != null && !"ASSEMBLY".equals(authority))
                    throw new FbdException("invalid_authority", "parse", null, "", "FBD generation_authority must be ASSEMBLY");
                if (c.features() != null && c.features().stream().anyMatch(f -> f != null &&
                        (f.startsWith("component_request:") || f.startsWith("group_request:") || f.startsWith("landmark:"))))
                    throw new FbdException("ambiguous_authority", "parse", null, "", "FBD cannot select a library or landmark generator");
                for (String alternative : ALTERNATIVES) if (c.params().containsKey(alternative))
                    throw new FbdException("ambiguous_authority", "parse", null, "", "fbd_program cannot coexist with " + alternative);
                for (Component other : components) {
                    if (other != null && other != c && Objects.equals(other.slotId(), c.slotId())
                            && !("ASSEMBLY".equals(other.componentType()) && other.params() != null
                                && other.params().containsKey("fbd_program"))
                            && Set.of("MASS_MAIN", "MAIN_MASS", "MASS_SECONDARY", "MASS_WING", "SIDE_WING",
                            "ROOF", "ROOF_STRUCTURE", "FACADE_WINDOWS", "ENTRANCE", "ASSEMBLY").contains(other.componentType()))
                        throw new FbdException("ambiguous_authority", "parse", null, "", "FBD requires its own primary slot; do not silently discard another building component");
                }
                var compiled = FbdCompiler.compile(c.params().get("fbd_program"));
                totalCost += compiled.cost(); totalSteps += compiled.steps(); totalCommands += compiled.commands().size();
                if (totalCost > Math.min(FbdLimits.DEFAULT.samples(), FbdLimits.DEFAULT.writes())
                        || totalSteps > FbdLimits.DEFAULT.steps() || totalCommands > FbdLimits.DEFAULT.commands())
                    throw new FbdException("budget_exceeded", "evaluate", null, "", "Cumulative plan FBD budget exceeded");
                var params = new HashMap<String, Object>(c.params());
                params.remove("fbd_program");
                params.put(COMPILED, compiled);
                params.put(SOURCE, new SourceContext("/components/" + i, c.slotId()));
                params.put("generation_authority", "ASSEMBLY");
                // Route hints see data, while the actual generator uses the trusted Compilation.
                params.put("assembly", Map.of("ops", compiled.commands().stream().map(FbdCompiler.Command::op).toList()));
                result.add(new Component(c.componentType(), c.slotId(), c.relativePosition(), c.dimensions(), c.features(), params));
            } catch (FbdException failure) {
                report(failure, path);
                return List.of();
            }
        }
        return result;
    }

    public static void report(FbdException failure, String prefix) {
        AssemblyCompileDiagnostics.set(new CapabilityGap("E_FBD_" + failure.code.toUpperCase(Locale.ROOT),
                (failure.node == null ? "" : "node=" + failure.node + ": ") + failure.getMessage(),
                prefix + failure.path, List.of("Fix the program at this path and resubmit; no partial geometry was returned.",
                        "stage=" + failure.stage), failure.diagnostic()));
    }
}
