package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Component;

import java.util.Collection;
import java.util.Locale;
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
}
