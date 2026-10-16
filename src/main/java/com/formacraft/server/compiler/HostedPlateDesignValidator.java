package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.BuildingGeometryContext;
import com.formacraft.common.generation.component.util.ComponentFootprintUtil;
import com.formacraft.common.generation.component.util.HostedPlateOccupancy;
import com.formacraft.common.generation.component.util.PlateRoleCatalog;
import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Vec3i;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Compile-time fidelity gates for hosted plates: role catalog, host binding, level envelope. */
final class HostedPlateDesignValidator {
    private HostedPlateDesignValidator() {}

    static Optional<CapabilityGap> check(LlmPlan plan, List<Component> components) {
        if (components == null) {
            return Optional.empty();
        }
        Optional<CapabilityGap> duplicateId = checkDuplicateComponentIds(components);
        if (duplicateId.isPresent()) {
            return duplicateId;
        }
        for (Component component : components) {
            if (!isHostedPlate(component)) {
                continue;
            }
            Optional<CapabilityGap> role = checkPlateRole(component);
            if (role.isPresent()) {
                return role;
            }
            Optional<CapabilityGap> host = checkHostResolved(component, components);
            if (host.isPresent()) {
                return host;
            }
            Optional<CapabilityGap> level = checkLevelEnvelope(component, components);
            if (level.isPresent()) {
                return level;
            }
        }
        return Optional.empty();
    }

    static Optional<CapabilityGap> checkDuplicateComponentIds(List<Component> components) {
        if (components == null) {
            return Optional.empty();
        }
        // Only hostable masses: satellites may legitimately reuse a display id in some fixtures.
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (Component c : components) {
            if (c == null || c.params() == null) {
                continue;
            }
            if ("plate".equalsIgnoreCase(String.valueOf(c.params().get("extrude_mode")))) {
                continue;
            }
            String type = c.componentType() == null ? "" : c.componentType().toUpperCase(Locale.ROOT);
            if (!isMassType(type)) {
                continue;
            }
            String id = paramString(c.params(), "component_id");
            if (id == null) {
                continue;
            }
            if (!seen.add(id)) {
                return Optional.of(new CapabilityGap(
                        "E_DUPLICATE_COMPONENT_ID",
                        "Duplicate params.component_id: " + id,
                        "components[].params.component_id",
                        List.of("Give every hostable mass a unique component_id.",
                                "host_id / attachments require unique mass ids.")));
            }
        }
        return Optional.empty();
    }

    /** After {@link BuildingGeometryContext#putMass}, interior plates must resolve host geometry. */
    static Optional<CapabilityGap> checkHostGeometry(List<Component> components) {
        if (components == null || !BuildingGeometryContext.isActive()) {
            return Optional.empty();
        }
        for (Component component : components) {
            if (!isHostedPlate(component)) {
                continue;
            }
            Map<String, Object> params = component.params();
            String hostId = paramString(params, "host_id", "hostId");
            if (hostId == null) {
                continue;
            }
            var role = HostedPlateOccupancy.resolveRole(params);
            if (HostedPlateOccupancy.skipsHostFootprintClip(role)) {
                continue;
            }
            if (BuildingGeometryContext.get(hostId) == null) {
                return Optional.of(new CapabilityGap(
                        "E_HOST_GEOMETRY_MISSING",
                        "Host geometry missing for interior plate host_id=" + hostId,
                        "components[].params.host_id",
                        List.of("Ensure the host mass has component_id and is not itself a plate.",
                                "Fix host_id spelling to match a mass component_id.")));
            }
        }
        return Optional.empty();
    }

    private static Optional<CapabilityGap> checkPlateRole(Component component) {
        Map<String, Object> params = component.params() == null ? Map.of() : component.params();
        Optional<String> explicit = PlateRoleCatalog.extractExplicit(params);
        if (explicit.isEmpty()) {
            return Optional.empty();
        }
        if (!PlateRoleCatalog.isSupportedExplicit(explicit.get())) {
            return Optional.of(new CapabilityGap(
                    "E_PLATE_ROLE_UNSUPPORTED",
                    "Unsupported plate_role: " + explicit.get(),
                    "components[].params.plate_role",
                    List.of("Use interior, terrace/balcony/deck/cantilever, or bridge/link/connector.",
                            "Omit plate_role for the interior technical default.")));
        }
        return Optional.empty();
    }

    private static Optional<CapabilityGap> checkHostResolved(Component plate, List<Component> components) {
        String hostId = paramString(plate.params(), "host_id", "hostId");
        if (hostId == null) {
            return Optional.empty();
        }
        if (findHostMass(hostId, components).isEmpty()) {
            return Optional.of(new CapabilityGap(
                    "E_HOST_ID_UNRESOLVED",
                    "No host mass with component_id=" + hostId + " for hosted plate",
                    "components[].params.host_id",
                    List.of("Set host_id to an existing mass component_id.",
                            "Do not point host_id at another plate.")));
        }
        return Optional.empty();
    }

    private static Optional<CapabilityGap> checkLevelEnvelope(Component plate, List<Component> components) {
        if (isAutoInferred(plate)) {
            return Optional.empty();
        }
        String hostId = paramString(plate.params(), "host_id", "hostId");
        if (hostId == null || plate.relativePosition() == null) {
            return Optional.empty();
        }
        Optional<Component> host = findHostMass(hostId, components);
        if (host.isEmpty() || host.get().dimensions() == null) {
            return Optional.empty();
        }
        Vec3i origin = ComponentFootprintUtil.resolveMinCornerOrigin(host.get());
        if (origin == null) {
            return Optional.empty();
        }
        var role = HostedPlateOccupancy.resolveRole(plate.params());
        var envelope = HostedPlateOccupancy.levelEnvelope(
                role, origin.y(), host.get().dimensions().height());
        if (envelope == null) {
            return Optional.empty();
        }
        int y = plate.relativePosition().y();
        if (!envelope.contains(y)) {
            return Optional.of(new CapabilityGap(
                    "E_HOSTED_PLATE_LEVEL_CONFLICT",
                    "Explicit plate Y=" + y + " is outside " + role + " envelope ["
                            + envelope.minInclusive() + "," + envelope.maxInclusive() + "]",
                    "components[].relative_position.y",
                    List.of("Keep interior plates within the host mass height.",
                            "Terrace may sit on the roof (host height + 1).",
                            "Bridge Y is not limited to a single host envelope.")));
        }
        return Optional.empty();
    }

    static Optional<Component> findHostMass(String hostId, List<Component> components) {
        if (hostId == null || components == null) {
            return Optional.empty();
        }
        for (Component c : components) {
            if (c == null || c.params() == null) {
                continue;
            }
            if ("plate".equalsIgnoreCase(String.valueOf(c.params().get("extrude_mode")))) {
                continue;
            }
            String type = c.componentType() == null ? "" : c.componentType().toUpperCase(Locale.ROOT);
            if (!isMassType(type)) {
                continue;
            }
            String id = paramString(c.params(), "component_id");
            if (hostId.equals(id)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    private static boolean isHostedPlate(Component component) {
        if (component == null || component.params() == null) {
            return false;
        }
        return "plate".equalsIgnoreCase(String.valueOf(component.params().get("extrude_mode")));
    }

    private static boolean isMassType(String type) {
        return "MASS_MAIN".equals(type) || "MAIN_MASS".equals(type)
                || "MASS_SECONDARY".equals(type) || "MASS_WING".equals(type) || "SIDE_WING".equals(type)
                || "TOWER".equals(type);
    }

    private static boolean isAutoInferred(Component component) {
        if (component == null || component.params() == null) {
            return false;
        }
        Object v = component.params().get("_compiler_inferred");
        if (v == null) {
            v = component.params().get("auto_inferred");
        }
        if (v == null) {
            v = component.params().get("autoInferred");
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v != null) {
            return "true".equalsIgnoreCase(v.toString().trim());
        }
        return false;
    }

    private static String paramString(Map<String, Object> params, String... keys) {
        if (params == null) {
            return null;
        }
        for (String key : keys) {
            Object raw = params.get(key);
            if (raw == null) {
                continue;
            }
            String value = String.valueOf(raw).trim();
            if (!value.isEmpty()) {
                return value;
            }
        }
        return null;
    }
}
