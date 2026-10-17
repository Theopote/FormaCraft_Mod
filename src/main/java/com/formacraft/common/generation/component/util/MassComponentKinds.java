package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Shared host-mass / circulation-mass classification (avoid duplicated type lists). */
public final class MassComponentKinds {
    private static final Set<String> HOST_MASS_TYPES = Set.of(
            "MASS_MAIN", "MAIN_MASS", "MASS_SECONDARY", "MASS_WING", "SIDE_WING", "TOWER");
    private static final Set<String> PRIMARY_ENTRY_TYPES = Set.of("MASS_MAIN", "MAIN_MASS");

    private MassComponentKinds() {}

    public static boolean isHostMass(String type) {
        if (type == null || type.isBlank()) {
            return false;
        }
        return HOST_MASS_TYPES.contains(type.trim().toUpperCase(Locale.ROOT));
    }

    public static boolean isHostMass(Component component) {
        return component != null && isHostMass(component.componentType());
    }

    public static boolean isPlate(Component component) {
        if (component == null || component.params() == null) {
            return false;
        }
        return "plate".equalsIgnoreCase(String.valueOf(component.params().get("extrude_mode")));
    }

    /** Host mass shell that can participate in circulation (not a hosted plate). */
    public static boolean isCirculationMass(Component component) {
        return isHostMass(component) && !isPlate(component) && component.dimensions() != null;
    }

    /** Circulation mass with at least two design storeys. */
    public static boolean isStoreyedCirculationMass(Component component) {
        if (!isCirculationMass(component)) {
            return false;
        }
        int envelope = Math.max(1, component.dimensions().height());
        ResolvedFloorLayout layout = ResolvedFloorLayout.from(component, envelope);
        return layout.floorCount() >= 2 && layout.floorYs().size() >= 2;
    }

    /** Standalone entry building (main mass without host_id). */
    public static boolean isPrimaryEntryMass(Component component) {
        if (!isCirculationMass(component) || component.componentType() == null) {
            return false;
        }
        String type = component.componentType().toUpperCase(Locale.ROOT);
        if (!PRIMARY_ENTRY_TYPES.contains(type)) {
            return false;
        }
        return hostId(component) == null;
    }

    public enum HostAttachment {
        /** No host_id — independent circulation unit. */
        INDEPENDENT,
        /** host_id resolves to another circulation mass. */
        ATTACHED,
        /** host_id present but does not resolve. */
        UNRESOLVED_HOST
    }

    public record Attachment(HostAttachment kind, Component host) {}

    public static Attachment classifyAttachment(Component component, Collection<Component> candidates) {
        if (!isCirculationMass(component)) {
            return new Attachment(HostAttachment.INDEPENDENT, null);
        }
        String hid = hostId(component);
        if (hid == null) {
            return new Attachment(HostAttachment.INDEPENDENT, null);
        }
        Component host = findCirculationMassById(hid, candidates);
        if (host == null || host == component || !isCirculationMass(host)) {
            return new Attachment(HostAttachment.UNRESOLVED_HOST, null);
        }
        return new Attachment(HostAttachment.ATTACHED, host);
    }

    public static boolean isAttachedCirculationMass(Component component, Collection<Component> candidates) {
        return classifyAttachment(component, candidates).kind() == HostAttachment.ATTACHED;
    }

    /**
     * Detect cycles in the host_id digraph among circulation masses.
     * Edges only when both ends resolve as circulation masses.
     */
    public static Optional<CapabilityGap> validateHostAcyclic(Collection<Component> circulationMasses) {
        if (circulationMasses == null || circulationMasses.isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> edges = new HashMap<>();
        for (Component mass : circulationMasses) {
            if (!isCirculationMass(mass)) {
                continue;
            }
            String id = componentId(mass);
            if (id == null) {
                continue;
            }
            Attachment att = classifyAttachment(mass, circulationMasses);
            if (att.kind() != HostAttachment.ATTACHED || att.host() == null) {
                continue;
            }
            String hostCid = componentId(att.host());
            if (hostCid == null) {
                continue;
            }
            edges.put(id, hostCid);
        }
        if (edges.isEmpty()) {
            return Optional.empty();
        }
        Set<String> visited = new HashSet<>();
        Set<String> stack = new HashSet<>();
        List<String> path = new ArrayList<>();
        for (String start : edges.keySet()) {
            Optional<List<String>> cycle = dfsCycle(start, edges, visited, stack, path);
            if (cycle.isPresent()) {
                return Optional.of(new CapabilityGap(
                        "E_CIRCULATION_HOST_CYCLE",
                        "host_id cycle among circulation masses: " + String.join(" -> ", cycle.get()),
                        "components[].params.host_id",
                        List.of("Break the host_id cycle so each attached mass chains to an independent root.",
                                "Do not point host_id at a descendant of the same mass.")));
            }
        }
        return Optional.empty();
    }

    private static Optional<List<String>> dfsCycle(
            String node,
            Map<String, String> edges,
            Set<String> visited,
            Set<String> stack,
            List<String> path
    ) {
        if (stack.contains(node)) {
            int idx = path.indexOf(node);
            List<String> cycle = new ArrayList<>(path.subList(Math.max(0, idx), path.size()));
            cycle.add(node);
            return Optional.of(cycle);
        }
        if (visited.contains(node)) {
            return Optional.empty();
        }
        visited.add(node);
        stack.add(node);
        path.add(node);
        String next = edges.get(node);
        if (next != null) {
            Optional<List<String>> cycle = dfsCycle(next, edges, visited, stack, path);
            if (cycle.isPresent()) {
                return cycle;
            }
        }
        path.remove(path.size() - 1);
        stack.remove(node);
        return Optional.empty();
    }

    /**
     * Walk host_id to the independent root (no host_id). Call only after {@link #validateHostAcyclic}.
     * Returns {@code mass} itself when independent; null if the chain cannot be resolved.
     */
    public static Component resolveIndependentHost(Component mass, Collection<Component> candidates) {
        if (mass == null || candidates == null) {
            return null;
        }
        Component cur = mass;
        int limit = candidates.size() + 1;
        for (int i = 0; i < limit; i++) {
            Attachment att = classifyAttachment(cur, candidates);
            if (att.kind() == HostAttachment.INDEPENDENT) {
                return cur;
            }
            if (att.kind() != HostAttachment.ATTACHED || att.host() == null) {
                return null;
            }
            cur = att.host();
        }
        return null;
    }

    public static String hostId(Component component) {
        if (component == null || component.params() == null) {
            return null;
        }
        Object v = component.params().get("host_id");
        if (v == null) {
            v = component.params().get("hostId");
        }
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() || "null".equalsIgnoreCase(s) ? null : s;
    }

    public static Component findCirculationMassById(String componentId, Collection<Component> candidates) {
        if (componentId == null || candidates == null) {
            return null;
        }
        for (Component c : candidates) {
            if (c == null || c.params() == null || !isCirculationMass(c)) {
                continue;
            }
            Object id = c.params().get("component_id");
            if (componentId.equals(String.valueOf(id))) {
                return c;
            }
        }
        return null;
    }

    private static String componentId(Component mass) {
        if (mass == null || mass.params() == null) {
            return null;
        }
        Object id = mass.params().get("component_id");
        if (id == null) {
            return null;
        }
        String s = String.valueOf(id).trim();
        return s.isEmpty() ? null : s;
    }
}
