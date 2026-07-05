package com.formacraft.server.assembly;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Public facade for resolving assembly graph component ports (used by validators and schema catalog).
 */
public final class AssemblyPortResolver {

    private AssemblyPortResolver() {}

    public static Set<String> portIds(Map<String, Object> component) {
        if (component == null || component.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(AssemblyComponentEmitter.buildPorts(component).keySet());
    }

    public static Map<String, int[]> ports(Map<String, Object> component) {
        if (component == null || component.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(AssemblyComponentEmitter.buildPorts(component));
    }
}
