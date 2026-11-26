package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.MassBooleanComposition;
import com.formacraft.common.geometry.shape.CsgOp;
import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Folds peer-mass boolean satellites into the host {@code params.masses[]} so
 * {@link com.formacraft.common.generation.component.impl.MassMainGenerator} emits one CSG solid.
 * Missing host → {@code E_MASS_CSG_HOST_MISSING}. Does not nudge positions (Phase 02.1 fidelity).
 */
final class MassCsgPeerFoldRepair {

    private MassCsgPeerFoldRepair() {}

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) {
            return plan;
        }
        Map<String, Integer> hostIndex = new HashMap<>();
        List<Component> working = new ArrayList<>(plan.components());
        for (int i = 0; i < working.size(); i++) {
            Component c = working.get(i);
            String id = idOf(c);
            if (!id.isBlank()) hostIndex.put(id, i);
        }

        boolean changed = false;
        for (int i = 0; i < working.size(); i++) {
            Component sat = working.get(i);
            if (sat == null || MassBooleanComposition.isFoldedCsgSatellite(sat)) continue;
            CsgOp op = MassBooleanComposition.peerMassOperation(sat);
            if (op == null) continue;
            // Nested masses[] CSG on a single component is handled by MassMainGenerator — skip fold.
            if (MassBooleanComposition.needsCsg(sat) && MassBooleanComposition.peerHostId(sat) == null) {
                continue;
            }
            String hostId = MassBooleanComposition.peerHostId(sat);
            if (hostId == null || hostId.isBlank()) {
                AssemblyCompileDiagnostics.set(new CapabilityGap(
                        "E_MASS_CSG_HOST_MISSING",
                        "mass_operation CSG satellite has no host_id / target_host_id",
                        "components[].params.host_id",
                        List.of(
                                "Set host_id to the target MASS component_id",
                                "Or nest the carve/intersect under params.masses[] on the host"
                        )
                ));
                return plan;
            }
            Integer hi = hostIndex.get(hostId);
            if (hi == null) {
                AssemblyCompileDiagnostics.set(new CapabilityGap(
                        "E_MASS_CSG_HOST_MISSING",
                        "mass_operation CSG satellite host_id=" + hostId + " not found",
                        "components[].params.host_id",
                        List.of("Use an exact hostable component_id", "Check intentional_interlock peer wiring")
                ));
                return plan;
            }
            Component host = working.get(hi);
            if (host == null || host.relativePosition() == null || sat.relativePosition() == null
                    || host.dimensions() == null || sat.dimensions() == null) {
                continue;
            }
            Vec3i hostOrigin = minCorner(host);
            Vec3i satOrigin = minCorner(sat);
            int ox = satOrigin.x() - hostOrigin.x();
            int oy = satOrigin.y() - hostOrigin.y();
            int oz = satOrigin.z() - hostOrigin.z();

            Map<String, Object> hostParams = host.params() == null
                    ? new HashMap<>()
                    : new HashMap<>(host.params());
            @SuppressWarnings("unchecked")
            List<Object> masses = hostParams.get("masses") instanceof List<?> list
                    ? new ArrayList<>(list)
                    : new ArrayList<>();
            if (masses.isEmpty()) {
                Dimensions hd = host.dimensions();
                masses.add(Map.of(
                        "operation", "add",
                        "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", hd.width(), "depth", hd.depth(), "height", hd.height())
                ));
            }
            Dimensions sd = sat.dimensions();
            Map<String, Object> child = new HashMap<>();
            child.put("operation", op.name().toLowerCase(Locale.ROOT));
            child.put("offset", Map.of("x", ox, "y", oy, "z", oz));
            child.put("dimensions", Map.of("width", sd.width(), "depth", sd.depth(), "height", sd.height()));
            masses.add(child);
            hostParams.put("masses", masses);
            hostParams.put("mass_boolean_composition", true);

            working.set(hi, new Component(
                    host.componentType(), host.slotId(), host.relativePosition(),
                    host.dimensions(), host.features(), hostParams));

            Map<String, Object> satParams = sat.params() == null
                    ? new HashMap<>()
                    : new HashMap<>(sat.params());
            satParams.put("mass_csg_folded", true);
            working.set(i, new Component(
                    sat.componentType(), sat.slotId(), sat.relativePosition(),
                    sat.dimensions(), sat.features(), satParams));
            changed = true;
        }
        return changed ? PlanComponentRewrite.withComponents(plan, working) : plan;
    }

    private static Vec3i minCorner(Component c) {
        Vec3i resolved = com.formacraft.common.generation.component.util.ComponentFootprintUtil
                .resolveMinCornerOrigin(c);
        return resolved == null ? c.relativePosition() : resolved;
    }

    private static String idOf(Component c) {
        if (c == null || c.params() == null) return "";
        Object id = c.params().get("component_id");
        if (id == null) id = c.params().get("componentId");
        return id == null ? "" : String.valueOf(id).trim();
    }
}
