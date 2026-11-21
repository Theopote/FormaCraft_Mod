package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Canonicalize {@code host_id}/{@code hostId} (and target aliases) before remap / align / validate.
 * Conflicting dual writes fail openly — never silently pick one.
 */
final class HostAliasNormalizer {
    private HostAliasNormalizer() {}

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) {
            return plan;
        }
        List<Component> out = new ArrayList<>(plan.components().size());
        boolean changed = false;
        List<String> conflicts = new ArrayList<>();
        for (Component c : plan.components()) {
            if (c == null || c.params() == null || c.params().isEmpty()) {
                out.add(c);
                continue;
            }
            Map<String, Object> params = new HashMap<>(c.params());
            String hostConflict = canonicalizePair(params, "host_id", "hostId");
            if (hostConflict != null) {
                conflicts.add(label(c) + " host:" + hostConflict);
            }
            String targetConflict = canonicalizePair(params, "target_host_id", "targetHostId");
            if (targetConflict != null) {
                conflicts.add(label(c) + " target_host:" + targetConflict);
            }
            if (!paramsEqual(c.params(), params)) {
                out.add(new Component(c.componentType(), c.slotId(), c.relativePosition(),
                        c.dimensions(), c.features(), params));
                changed = true;
            } else {
                out.add(c);
            }
        }
        if (!conflicts.isEmpty() && !AssemblyCompileDiagnostics.hasGap() && !plan.hasCapabilityGap()) {
            AssemblyCompileDiagnostics.set(new CapabilityGap(
                    "E_HOST_ID_ALIAS_CONFLICT",
                    "params.host_id / hostId (or target aliases) disagree: "
                            + String.join(", ", conflicts),
                    "components[].params.host_id",
                    List.of(
                            "Use a single canonical host_id (snake_case).",
                            "Do not dual-write host_id and hostId with different values."),
                    Map.of("alias_conflicts", List.copyOf(conflicts))));
        }
        return changed ? PlanComponentRewrite.withComponents(plan, out) : plan;
    }

    /**
     * @return conflict description when both keys present with different non-blank values;
     *         otherwise null and params are normalized to the snake_case key only.
     */
    static String canonicalizePair(Map<String, Object> params, String canonical, String alias) {
        String a = stringOrNull(params.get(canonical));
        String b = stringOrNull(params.get(alias));
        if (a != null && b != null && !a.equals(b)) {
            return a + "≠" + b;
        }
        if (a == null && b != null) {
            params.put(canonical, b);
        }
        params.remove(alias);
        return null;
    }

    private static boolean paramsEqual(Map<String, Object> a, Map<String, Object> b) {
        return a.size() == b.size() && a.entrySet().stream()
                .allMatch(e -> Objects.equals(e.getValue(), b.get(e.getKey())));
    }

    private static String stringOrNull(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static String label(Component c) {
        String id = ComponentPlanTypes.getParamString(c.params(), "component_id", "componentId");
        return id != null ? id : String.valueOf(c.componentType());
    }
}
