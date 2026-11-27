package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.ComponentFootprintUtil;
import com.formacraft.common.generation.component.util.MassBooleanComposition;
import com.formacraft.common.generation.component.util.ResolvedComponentGeometry;
import com.formacraft.common.geometry.shape.CsgOp;
import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Folds peer-mass boolean satellites into the host {@code params.masses[]} so
 * {@link com.formacraft.common.generation.component.impl.MassMainGenerator} emits one CSG solid.
 * <p>
 * Phase 02.2.1: deltas use plan-space origins ({@code local + slot anchor}), reject self/cyclic
 * hosts, preserve child identity/material, refuse unsupported non-box shapes.
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

        // Collect fold edges sat -> host for cycle / depth checks.
        List<int[]> foldEdges = new ArrayList<>();
        for (int i = 0; i < working.size(); i++) {
            Component sat = working.get(i);
            if (sat == null || MassBooleanComposition.isFoldedCsgSatellite(sat)) continue;
            CsgOp op;
            try {
                op = MassBooleanComposition.peerMassOperation(sat);
            } catch (IllegalArgumentException ex) {
                AssemblyCompileDiagnostics.set(gapFromException(ex));
                return plan;
            }
            if (op == null) continue;
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
            String satId = idOf(sat);
            if (hostId.equals(satId)) {
                AssemblyCompileDiagnostics.set(new CapabilityGap(
                        "E_MASS_CSG_HOST_CYCLE",
                        "mass_operation satellite host_id equals its own component_id",
                        "components[].params.host_id",
                        List.of("Point host_id at a different MASS host")
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
            if (!isMassHost(host)) {
                AssemblyCompileDiagnostics.set(new CapabilityGap(
                        "E_MASS_CSG_HOST_INVALID",
                        "mass_operation host_id=" + hostId + " is not a MASS/TOWER host",
                        "components[].params.host_id",
                        List.of("Bind CSG satellites to MASS_MAIN / MASS_SECONDARY / TOWER")
                ));
                return plan;
            }
            foldEdges.add(new int[] {i, hi});
        }

        if (hasCycle(working, foldEdges)) {
            AssemblyCompileDiagnostics.set(new CapabilityGap(
                    "E_MASS_CSG_HOST_CYCLE",
                    "mass_operation host bindings form a cycle",
                    "components[].params.host_id",
                    List.of("Use a DAG of host bindings", "Flatten multi-level CSG onto one host")
            ));
            return plan;
        }
        if (hasMultiLevelFold(working, foldEdges)) {
            AssemblyCompileDiagnostics.set(new CapabilityGap(
                    "E_MASS_CSG_HOST_DEPTH",
                    "multi-level mass_operation fold (A→B→C) is unsupported in Phase 02.2.1",
                    "components[].params.host_id",
                    List.of("Fold all satellites onto a single root host", "Or nest ops under one component masses[]")
            ));
            return plan;
        }

        boolean changed = false;
        // Deterministic order: lower satellite index first.
        foldEdges.sort((a, b) -> Integer.compare(a[0], b[0]));
        for (int[] edge : foldEdges) {
            int i = edge[0];
            int hi = edge[1];
            Component sat = working.get(i);
            if (sat == null || MassBooleanComposition.isFoldedCsgSatellite(sat)) continue;
            CsgOp op = MassBooleanComposition.peerMassOperation(sat);
            Component host = working.get(hi);
            if (host == null || host.relativePosition() == null || sat.relativePosition() == null
                    || host.dimensions() == null || sat.dimensions() == null) {
                continue;
            }

            try {
                assertBoxShape(sat);
            } catch (IllegalArgumentException ex) {
                AssemblyCompileDiagnostics.set(gapFromException(ex));
                return plan;
            }

            Vec3i hostOrigin = planOrigin(plan, host);
            Vec3i satOrigin = planOrigin(plan, sat);
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
                Map<String, Object> base = new HashMap<>();
                base.put("operation", "add");
                base.put("offset", Map.of("x", 0, "y", 0, "z", 0));
                base.put("dimensions", Map.of("width", hd.width(), "depth", hd.depth(), "height", hd.height()));
                if (idOf(host) != null && !idOf(host).isBlank()) {
                    base.put("part_id", idOf(host));
                }
                masses.add(base);
            }
            Dimensions sd = sat.dimensions();
            Map<String, Object> child = new HashMap<>();
            child.put("operation", op.name().toLowerCase(Locale.ROOT));
            child.put("offset", Map.of("x", ox, "y", oy, "z", oz));
            child.put("dimensions", Map.of("width", sd.width(), "depth", sd.depth(), "height", sd.height()));
            if (!idOf(sat).isBlank()) child.put("part_id", idOf(sat));
            // Preserve material / shape provenance from the satellite.
            if (sat.params() != null) {
                for (String key : List.of("material", "wall_block", "wallBlock", "block", "block_id",
                        "shape", "kind", "footprint", "volume_geometry", "volumeGeometry", "component_id")) {
                    if (sat.params().containsKey(key) && !child.containsKey(key)) {
                        child.put(key, sat.params().get(key));
                    }
                }
            }
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

    /** Plan-space min-corner: resolved local origin + slot anchor (P0-02). */
    private static Vec3i planOrigin(LlmPlan plan, Component c) {
        ResolvedComponentGeometry geometry = ResolvedComponentGeometry.resolve(c);
        Vec3i slot = slotAnchor(plan, c == null ? null : c.slotId());
        if (geometry != null) {
            return geometry.originInPlan(slot);
        }
        Vec3i local = ComponentFootprintUtil.resolveMinCornerOrigin(c);
        if (local == null) local = c.relativePosition();
        if (local == null) return new Vec3i(0, 0, 0);
        if (slot == null) return local;
        return new Vec3i(local.x() + slot.x(), local.y() + slot.y(), local.z() + slot.z());
    }

    private static Vec3i slotAnchor(LlmPlan plan, String slotId) {
        if (slotId == null || slotId.isBlank() || plan == null || plan.layout() == null
                || plan.layout().slots() == null) {
            return new Vec3i(0, 0, 0);
        }
        for (Slot slot : plan.layout().slots()) {
            if (slot != null && slotId.equals(slot.slotId()) && slot.anchor() != null) {
                return slot.anchor();
            }
        }
        return new Vec3i(0, 0, 0);
    }

    private static boolean isMassHost(Component c) {
        if (c == null || c.componentType() == null) return false;
        String t = c.componentType().trim().toUpperCase(Locale.ROOT);
        return t.startsWith("MASS") || "MAIN_MASS".equals(t) || "TOWER".equals(t) || "GATE".equals(t);
    }

    private static void assertBoxShape(Component sat) {
        if (sat.params() == null) return;
        // Preserve/check satellite volume_geometry — reject non-box (Phase 02.3.2 Task E).
        for (String key : List.of("volume_geometry", "volumeGeometry", "shape", "kind", "footprint")) {
            Object shape = sat.params().get(key);
            if (shape == null) continue;
            String s = String.valueOf(shape).trim().toLowerCase(Locale.ROOT);
            if (s.isEmpty() || "box".equals(s) || "rect".equals(s) || "rectangle".equals(s)
                    || "cube".equals(s) || "aabb".equals(s)) {
                continue;
            }
            throw new IllegalArgumentException(
                    "E_MASS_CSG_SHAPE_UNSUPPORTED: peer mass " + key + "=" + s
                            + " (only box/rect supported for CSG fold)");
        }
    }

    private static boolean hasCycle(List<Component> working, List<int[]> edges) {
        Map<Integer, Integer> satToHost = new HashMap<>();
        for (int[] e : edges) satToHost.put(e[0], e[1]);
        for (int start : satToHost.keySet()) {
            Set<Integer> seen = new HashSet<>();
            int cur = start;
            while (satToHost.containsKey(cur)) {
                if (!seen.add(cur)) return true;
                cur = satToHost.get(cur);
            }
        }
        // Also: host that is itself a CSG satellite of another.
        for (int[] e : edges) {
            Component host = working.get(e[1]);
            if (host != null && MassBooleanComposition.peerMassOperation(host) != null
                    && MassBooleanComposition.peerHostId(host) != null) {
                return true; // treated as multi-level; also flagged by hasMultiLevelFold
            }
        }
        return false;
    }

    private static boolean hasMultiLevelFold(List<Component> working, List<int[]> edges) {
        Set<Integer> satellites = new HashSet<>();
        for (int[] e : edges) satellites.add(e[0]);
        for (int[] e : edges) {
            if (satellites.contains(e[1])) return true;
            Component host = working.get(e[1]);
            if (host != null && MassBooleanComposition.peerMassOperation(host) != null
                    && MassBooleanComposition.peerHostId(host) != null) {
                return true;
            }
        }
        return false;
    }

    private static CapabilityGap gapFromException(IllegalArgumentException ex) {
        String msg = ex.getMessage() == null ? "mass CSG fold failed" : ex.getMessage();
        String code = msg.startsWith("E_MASS_CSG_")
                ? msg.substring(0, msg.indexOf(':') > 0 ? msg.indexOf(':') : msg.length())
                : "E_MASS_CSG_INVALID";
        return new CapabilityGap(code, msg, "components[].params",
                List.of("Use box/rect peer masses", "Fix host_id bindings"));
    }

    private static String idOf(Component c) {
        if (c == null || c.params() == null) return "";
        Object id = c.params().get("component_id");
        if (id == null) id = c.params().get("componentId");
        return id == null ? "" : String.valueOf(id).trim();
    }
}
