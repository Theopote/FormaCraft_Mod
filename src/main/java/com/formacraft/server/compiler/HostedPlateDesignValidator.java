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
            Optional<CapabilityGap> bridgeLevel = checkBridgeLevelConflict(component, components);
            if (bridgeLevel.isPresent()) {
                return bridgeLevel;
            }
            Optional<CapabilityGap> level = checkLevelEnvelope(component, components);
            if (level.isPresent()) {
                return level;
            }
            Optional<CapabilityGap> endpoints = checkBridgeEndpoints(component, components, plan);
            if (endpoints.isPresent()) {
                return endpoints;
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
        var role = HostedPlateOccupancy.resolveRole(plate.params());
        if (role == HostedPlateOccupancy.PlateRole.BRIDGE) {
            Optional<String> target = HostedPlateOccupancy.targetHostId(plate.params());
            if (target.isEmpty()) {
                return Optional.of(new CapabilityGap(
                        "E_TARGET_HOST_ID_REQUIRED",
                        "Bridge plate requires target_host_id",
                        "components[].params.target_host_id",
                        List.of("Set target_host_id to the second mass component_id.",
                                "host_id is the primary mass; target_host_id is the far end.")));
            }
            if (hostId != null && hostId.equals(target.get())) {
                return Optional.of(new CapabilityGap(
                        "E_TARGET_HOST_ID_UNRESOLVED",
                        "target_host_id must differ from host_id",
                        "components[].params.target_host_id",
                        List.of("Point target_host_id at a different mass than host_id.")));
            }
            if (findBridgeEndpoint(target.get(), components).isEmpty()) {
                return Optional.of(new CapabilityGap(
                        "E_TARGET_HOST_ID_UNRESOLVED",
                        "No host mass with component_id=" + target.get() + " for bridge target_host_id",
                        "components[].params.target_host_id",
                        List.of("Set target_host_id to an existing mass component_id.")));
            }
        }
        if (hostId == null) {
            return Optional.empty();
        }
        if ((role == HostedPlateOccupancy.PlateRole.BRIDGE ? findBridgeEndpoint(hostId, components)
                : findHostMass(hostId, components)).isEmpty()) {
            return Optional.of(new CapabilityGap(
                    "E_HOST_ID_UNRESOLVED",
                    "No host mass with component_id=" + hostId + " for hosted plate",
                    "components[].params.host_id",
                    List.of("Set host_id to an existing mass component_id.",
                            "Do not point host_id at another plate.")));
        }
        return Optional.empty();
    }

    private static Optional<CapabilityGap> checkBridgeEndpoints(Component plate, List<Component> components, LlmPlan plan) {
        var role = HostedPlateOccupancy.resolveRole(plate.params());
        if (role != HostedPlateOccupancy.PlateRole.BRIDGE) {
            return Optional.empty();
        }
        String hostId = paramString(plate.params(), "host_id", "hostId");
        Optional<String> targetId = HostedPlateOccupancy.targetHostId(plate.params());
        if (hostId == null || targetId.isEmpty() || plate.dimensions() == null || plate.relativePosition() == null) {
            return Optional.empty();
        }
        Optional<Component> host = findBridgeEndpoint(hostId, components);
        Optional<Component> target = findBridgeEndpoint(targetId.get(), components);
        if (host.isEmpty() || target.isEmpty()) {
            return Optional.empty();
        }
        Map<String, com.formacraft.common.llm.dto.Slot> slots = new java.util.HashMap<>();
        if (plan != null && plan.layout() != null && plan.layout().slots() != null) {
            for (var slot : plan.layout().slots()) if (slot != null) slots.put(slot.slotId(), slot);
        }
        host = Optional.of(ComponentPlanCompiler.inSlotFrame(host.get(), plate.slotId(), slots));
        target = Optional.of(ComponentPlanCompiler.inSlotFrame(target.get(), plate.slotId(), slots));
        var plateBounds = ComponentFootprintUtil.bounds(plate);
        var hostBounds = ComponentFootprintUtil.bounds(host.get());
        var targetBounds = ComponentFootprintUtil.bounds(target.get());
        boolean hostXz = HostedPlateOccupancy.touchesEndpoint(plateBounds, hostBounds);
        boolean targetXz = HostedPlateOccupancy.touchesEndpoint(plateBounds, targetBounds);
        if (!hostXz || !targetXz) {
            return Optional.of(new CapabilityGap(
                    "E_BRIDGE_ENDPOINT_NO_CONTACT",
                    "Bridge plate does not touch both host_id=" + hostId + " and target_host_id=" + targetId.get(),
                    "components[].relative_position",
                    List.of("Extend the bridge AABB to meet both mass footprints.",
                            "Endpoints may be flush (face-adjacent); they need not overwrite shells.")));
        }
        int plateY = plate.relativePosition().y();
        boolean hostY = HostedPlateOccupancy.endpointLevelMatches(plateY, host.get());
        boolean targetY = HostedPlateOccupancy.endpointLevelMatches(plateY, target.get());
        if (!hostY || !targetY) {
            return Optional.of(new CapabilityGap(
                    "E_BRIDGE_ENDPOINT_LEVEL_MISMATCH",
                    "Bridge Y=" + plateY + " is outside the vertical envelope of "
                            + (!hostY ? "host_id=" + hostId : "target_host_id=" + targetId.get()),
                    "components[].relative_position.y",
                    List.of("Place the bridge deck within each mass height envelope.",
                            "XZ contact alone is not enough — endpoints must share a connectable storey.")));
        }
        return Optional.empty();
    }

    /** Explicit bridge Y must agree with connection_level when both are authored. */
    static Optional<CapabilityGap> checkBridgeLevelConflict(Component plate, List<Component> components) {
        if (!isHostedPlate(plate) || isAutoInferred(plate)) {
            return Optional.empty();
        }
        if (HostedPlateOccupancy.resolveRole(plate.params()) != HostedPlateOccupancy.PlateRole.BRIDGE) {
            return Optional.empty();
        }
        Optional<Integer> level = HostedPlateOccupancy.connectionLevel(plate.params());
        if (level.isEmpty() || plate.relativePosition() == null) {
            return Optional.empty();
        }
        String hostId = paramString(plate.params(), "host_id", "hostId");
        Optional<Component> host = findBridgeEndpoint(hostId, components);
        if (host.isEmpty()) {
            return Optional.empty();
        }
        Vec3i origin = ComponentFootprintUtil.resolveMinCornerOrigin(host.get());
        int fh = com.formacraft.common.generation.component.util.ComponentParamParsers
                .intParam(host.get().params(), "floor_height", "floorHeight");
        if (origin == null || fh <= 0) {
            return Optional.empty();
        }
        int expected = HostedPlateOccupancy.connectionY(plate, host.get(), fh);
        if (plate.relativePosition().y() != expected) {
            return Optional.of(new CapabilityGap(
                    "E_BRIDGE_LEVEL_CONFLICT",
                    "Explicit bridge Y=" + plate.relativePosition().y()
                            + " conflicts with connection_level=" + level.get()
                            + " expected Y=" + expected,
                    "components[].relative_position.y",
                    List.of("Align relative_position.y with hostOrigin.y + connection_level * floor_height.",
                            "Or omit connection_level to keep the authored bridge Y.")));
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
        // connection_level is applied in prepare (sets Y); skip single-host envelope for bridges.
        if (role == HostedPlateOccupancy.PlateRole.BRIDGE) {
            return Optional.empty();
        }
        var envelope = HostedPlateOccupancy.levelEnvelope(
                role, origin.y(), hostedEnvelopeHeight(host.get(), components));
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

    static Optional<Component> findBridgeEndpoint(String hostId, List<Component> components) {
        var mass = findHostMass(hostId,components);
        if (mass.isPresent() || hostId == null || components == null) return mass;
        return components.stream().filter(c -> c != null && c.params() != null && c.dimensions() != null
                && isMassType(c.componentType())
                && hostId.equals(paramString(c.params(),"component_id"))
                && "plate".equalsIgnoreCase(String.valueOf(c.params().get("extrude_mode")))
                && HostedPlateOccupancy.resolveRole(c.params()) == HostedPlateOccupancy.PlateRole.TERRACE
                && paramString(c.params(),"host_id") == null).findFirst();
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

    /** A stepped building's upper tiers extend the named root's interior envelope. */
    static int hostedEnvelopeHeight(Component host, List<Component> components) {
        int height = host.dimensions().height();
        String id = paramString(host.params(), "component_id");
        Vec3i origin = ComponentFootprintUtil.resolveMinCornerOrigin(host);
        for (Component tier : components) {
            if (tier == null || tier.dimensions() == null || tier.params() == null
                    || !com.formacraft.common.generation.component.util.MassComponentKinds.isCirculationMass(tier)
                    || !tier.params().containsKey("tier_index")
                    || !java.util.Objects.equals(host.slotId(), tier.slotId())
                    || !java.util.Objects.equals(id, paramString(tier.params(), "host_id"))) continue;
            Vec3i tierOrigin = ComponentFootprintUtil.resolveMinCornerOrigin(tier);
            if (tierOrigin != null) height = Math.max(height, tierOrigin.y() + tier.dimensions().height() - origin.y());
        }
        return height;
    }

    private static boolean isHostedPlate(Component component) {
        if (component == null || component.params() == null) {
            return false;
        }
        return com.formacraft.common.generation.component.util.MassComponentKinds.isHostMass(component)
                && "plate".equalsIgnoreCase(String.valueOf(component.params().get("extrude_mode")));
    }

    private static boolean isMassType(String type) {
        return com.formacraft.common.generation.component.util.MassComponentKinds.isHostMass(type);
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
