package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.BridgeWalkability;
import com.formacraft.common.generation.component.util.HostedPlateOccupancy;
import com.formacraft.common.generation.component.util.MassComponentKinds;
import com.formacraft.common.generation.component.util.PlannedOccupancy;
import com.formacraft.common.generation.component.util.ResolvedComponentGeometry;
import com.formacraft.common.generation.component.util.ResolvedFloorLayout;
import com.formacraft.common.generation.component.util.WalkabilityGraph;
import com.formacraft.common.generation.design.CirculationScope;
import com.formacraft.common.generation.design.DesignRequirement;
import com.formacraft.common.generation.design.DesignRequirementAccess;
import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * When {@code design_requirements.circulation=REQUIRED}, prove storey / complex / attached
 * reachability per {@link CirculationScope}. Entries resolve along the host_id chain.
 */
final class BuildingCirculationValidator {
    private BuildingCirculationValidator() {}

    private record MassEntry(
            Component mass,
            Vec3i ownGroundOrNull,
            Component hostOrNull,
            Vec3i rootGroundOrNull,
            boolean attached,
            /** Host-link / bridge starts — never includes ownGround for attached masses. */
            List<Vec3i> hostLinkEntries,
            /** Internal storey checks — root + own when standable. */
            List<Vec3i> localEntries
    ) {}

    static Optional<CapabilityGap> validate(
            LlmPlan plan,
            List<Component> components,
            List<BlockPatch> patches,
            Map<String, Slot> slotMap
    ) {
        if (DesignRequirementAccess.circulation(plan) != DesignRequirement.REQUIRED) {
            return Optional.empty();
        }
        if (components == null || components.isEmpty() || patches == null || patches.isEmpty()) {
            return dataMissing("Circulation REQUIRED but compile produced no components/patches to verify.");
        }

        Optional<CapabilityGap> scopeGap = DesignRequirementAccess.validateCirculationScope(plan);
        if (scopeGap.isPresent()) {
            return scopeGap;
        }

        List<Component> circulationMasses = new ArrayList<>();
        for (Component c : components) {
            if (MassComponentKinds.isCirculationMass(c)) {
                circulationMasses.add(c);
            }
        }
        if (circulationMasses.isEmpty()) {
            return dataMissing("Circulation REQUIRED but plan has no host mass shells to verify.");
        }

        Optional<CapabilityGap> cycleGap = MassComponentKinds.validateHostAcyclic(circulationMasses);
        if (cycleGap.isPresent()) {
            return cycleGap;
        }

        CirculationScope scope = DesignRequirementAccess.circulationScope(plan);
        var occ = BridgeWalkability.occupancyFromPatches(patches);
        String precision = String.valueOf(occ.precision());
        Vec3i zero = new Vec3i(0, 0, 0);

        // First pass: own grounds + complex root (independent only)
        Map<Component, Vec3i> ownGroundByMass = new HashMap<>();
        Vec3i complexRoot = null;
        for (Component mass : circulationMasses) {
            var attachment = MassComponentKinds.classifyAttachment(mass, circulationMasses);
            if (attachment.kind() == MassComponentKinds.HostAttachment.UNRESOLVED_HOST) {
                return incomplete(
                        "Circulation mass host_id unresolved (component_id=" + componentId(mass)
                                + ", host_id=" + MassComponentKinds.hostId(mass) + ", precision=" + precision + ")",
                        "Point host_id at a circulation mass component_id, or omit host_id for an independent unit.");
            }
            boolean attached = attachment.kind() == MassComponentKinds.HostAttachment.ATTACHED;
            int groundY = groundStoreyY(mass);
            Vec3i ownGround = shift(
                    BridgeWalkability.interiorFloorCell(mass, groundY),
                    slotAnchorOrZero(slotMap, mass.slotId(), zero));
            boolean ownStandable = ownGround != null && WalkabilityGraph.canStand(occ, ownGround);
            if (ownStandable) {
                ownGroundByMass.put(mass, ownGround);
            }
            if (!attached && !ownStandable) {
                return incomplete(
                        "Mass ground interior is not a legal standing cell (component_id="
                                + componentId(mass) + ", y=" + groundY + ", precision=" + precision + ")",
                        "Add a solid floor with 2-high clearance at the ground storey interior, "
                                + "or set host_id if this is an attached aerial mass.");
            }
            if (!attached && ownStandable && complexRoot == null) {
                complexRoot = ownGround;
            }
        }
        if (complexRoot == null) {
            return dataMissing(
                    "Circulation REQUIRED but no independent mass has a standable ground entry.");
        }

        // Second pass: resolve root grounds + entry lists
        List<MassEntry> entries = new ArrayList<>();
        Map<String, MassEntry> byId = new HashMap<>();
        for (Component mass : circulationMasses) {
            var attachment = MassComponentKinds.classifyAttachment(mass, circulationMasses);
            boolean attached = attachment.kind() == MassComponentKinds.HostAttachment.ATTACHED;
            Vec3i ownGround = ownGroundByMass.get(mass);
            Component directHost = attachment.host();
            Vec3i rootGround = null;
            if (attached) {
                Component root = MassComponentKinds.resolveIndependentHost(mass, circulationMasses);
                if (root == null) {
                    return incomplete(
                            "Attached mass host chain unresolved (component_id=" + componentId(mass)
                                    + ", precision=" + precision + ")",
                            "Point host_id at a chain ending in an independent circulation mass.");
                }
                rootGround = ownGroundByMass.get(root);
                if (rootGround == null) {
                    int rootY = groundStoreyY(root);
                    rootGround = shift(
                            BridgeWalkability.interiorFloorCell(root, rootY),
                            slotAnchorOrZero(slotMap, root.slotId(), zero));
                }
                if (rootGround == null || !WalkabilityGraph.canStand(occ, rootGround)) {
                    return incomplete(
                            "Attached mass root ground is not standable (component_id=" + componentId(mass)
                                    + ", root_id=" + componentId(root) + ", precision=" + precision + ")",
                            "Ensure the independent host mass has a walkable ground interior.");
                }
            }
            List<Vec3i> hostLink = buildHostLinkEntries(scope, attached, rootGround, ownGround, complexRoot);
            List<Vec3i> local = buildLocalEntries(scope, attached, rootGround, ownGround, complexRoot);
            MassEntry entry = new MassEntry(
                    mass, ownGround, directHost, rootGround, attached, hostLink, local);
            entries.add(entry);
            byId.put(componentId(mass), entry);
        }

        if (scope == CirculationScope.CONNECTED_COMPLEX && circulationMasses.size() >= 2) {
            for (MassEntry entry : entries) {
                if (entry.ownGroundOrNull() != null && entry.ownGroundOrNull().equals(complexRoot)
                        && !entry.attached()) {
                    continue;
                }
                Optional<CapabilityGap> join = massJoinsComplexFromRoot(
                        occ, complexRoot, entry.mass(), slotMap, zero, precision);
                if (join.isPresent()) {
                    return join;
                }
            }
        }

        // Attached: prove host-root → standable sample (never start from ownGround)
        for (MassEntry entry : entries) {
            if (!entry.attached()) {
                continue;
            }
            if (entry.hostLinkEntries().isEmpty()) {
                return incomplete(
                        "No circulation entry for attached mass (component_id=" + componentId(entry.mass())
                                + ", precision=" + precision + ")",
                        "Provide a host_id chain to an independent mass with a walkable ground.");
            }
            Vec3i goal = firstStandableSample(occ, entry.mass(), entry.ownGroundOrNull(), slotMap, zero);
            if (goal == null) {
                return incomplete(
                        "Attached mass has no standable interior sample (component_id="
                                + componentId(entry.mass()) + ", precision=" + precision + ")",
                        "Ensure the attached mass has a solid floor with clearance at an interior cell.");
            }
            Optional<CapabilityGap> attachedGap = reachFromAnyGround(
                    occ, entry.hostLinkEntries(), goal, componentId(entry.mass()), goal.y(), precision, scope,
                    "Attached mass not reachable from host entry (component_id="
                            + componentId(entry.mass()) + ", toY=" + goal.y()
                            + ", scope=" + scope + ", precision=" + precision + ")",
                    "Connect the attached mass with a bridge, corridor, or stairs from the host entry.");
            if (attachedGap.isPresent()) {
                return attachedGap;
            }
        }

        for (MassEntry entry : entries) {
            Component mass = entry.mass();
            if (!MassComponentKinds.isStoreyedCirculationMass(mass)) {
                continue;
            }
            int envelope = Math.max(1, mass.dimensions().height());
            ResolvedFloorLayout layout = ResolvedFloorLayout.from(mass, envelope);
            Vec3i massOff = slotAnchorOrZero(slotMap, mass.slotId(), zero);
            List<Vec3i> storeyGrounds = entry.localEntries();
            if (storeyGrounds.isEmpty()) {
                return incomplete(
                        "No circulation entry for storeyed mass (component_id=" + componentId(mass)
                                + ", precision=" + precision + ")",
                        "Provide a standable ground or a host_id with a walkable host ground.");
            }
            for (int i = 1; i < layout.floorYs().size(); i++) {
                int storeyY = layout.floorYs().get(i);
                Vec3i goal = shift(BridgeWalkability.interiorFloorCell(mass, storeyY), massOff);
                if (goal == null || !WalkabilityGraph.canStand(occ, goal)) {
                    return incomplete(
                            "Mass storey interior is not a legal standing cell (component_id="
                                    + componentId(mass) + ", y=" + storeyY + ", precision=" + precision + ")",
                            "Ensure each design storey has a solid floor and clearance at the interior sample.");
                }
                Optional<CapabilityGap> storeyGap = reachFromAnyGround(
                        occ, storeyGrounds, goal, componentId(mass), storeyY, precision, scope,
                        "Storey not reachable (component_id=" + componentId(mass) + ", toY=" + storeyY
                                + ", scope=" + scope + ", precision=" + precision + ")",
                        scope == CirculationScope.BUILDING_LOCAL
                                ? "Add stairs inside this mass, attach via host_id, or use CONNECTED_COMPLEX with bridges."
                                : "Add stairs or bridge access so each design storey joins the complex network.");
                if (storeyGap.isPresent()) {
                    return storeyGap;
                }
            }
        }

        for (Component plate : components) {
            if (plate == null || plate.params() == null
                    || !HostedPlateOccupancy.isBridgePlate(plate.params())) {
                continue;
            }
            if (plate.relativePosition() == null) {
                return incomplete(
                        "Bridge plate missing relative_position (precision=" + precision + ")",
                        "Author an explicit bridge plate Y.");
            }
            String hostId = paramString(plate.params(), "host_id", "hostId");
            String targetId = paramString(plate.params(), "target_host_id", "targetHostId");
            var host = HostedPlateDesignValidator.findHostMass(hostId, components);
            var target = HostedPlateDesignValidator.findHostMass(targetId, components);
            if (host.isEmpty() || target.isEmpty()) {
                continue;
            }
            int plateY = plate.relativePosition().y();
            Vec3i targetOff = slotAnchorOrZero(slotMap, target.get().slotId(), zero);
            Vec3i goal = shift(BridgeWalkability.interiorFloorCell(target.get(), plateY), targetOff);
            if (goal == null || !WalkabilityGraph.canStand(occ, goal)) {
                return incomplete(
                        "Bridge target interior at plate Y not standable (target_host_id=" + targetId
                                + ", y=" + plateY + ", precision=" + precision + ")",
                        "Ensure the target mass has a floor at the bridge connection level.");
            }
            List<Vec3i> startCandidates = bridgeStartEntries(
                    byId, host.get(), slotMap, zero, occ, complexRoot, scope);
            if (startCandidates.isEmpty()) {
                return incomplete(
                        "Bridge host has no circulation entry (host_id=" + hostId
                                + ", precision=" + precision + ")",
                        "Ensure the bridge host (or its independent root) has a walkable ground.");
            }
            Optional<CapabilityGap> bridgeGap = reachFromAnyGround(
                    occ, startCandidates, goal, hostId, plateY, precision, scope,
                    "Cannot walk from host entry to target at bridge level (host_id=" + hostId
                            + ", target_host_id=" + targetId + ", plateY=" + plateY
                            + ", precision=" + precision + ")",
                    "Add vertical circulation in the host (or both masses) up to the bridge storey.");
            if (bridgeGap.isPresent()) {
                // Rewrite search-limit message for bridge context
                CapabilityGap g = bridgeGap.get();
                if ("E_WALKABILITY_SEARCH_LIMIT".equals(g.code())) {
                    return Optional.of(new CapabilityGap(
                            "E_WALKABILITY_SEARCH_LIMIT",
                            "Building circulation search exhausted its budget for bridge end-to-end (host_id="
                                    + hostId + ", target_host_id=" + targetId + ", precision=" + precision + ")",
                            "components[]",
                            List.of("Add stairs to the bridge storey or simplify the plan.",
                                    "This is not a proof that no path exists — only that search stopped early.")));
                }
                return bridgeGap;
            }
        }
        return Optional.empty();
    }

    /** Starts for host-link proof and bridges — attached masses exclude ownGround. */
    private static List<Vec3i> buildHostLinkEntries(
            CirculationScope scope,
            boolean attached,
            Vec3i rootGround,
            Vec3i ownGround,
            Vec3i complexRoot
    ) {
        if (scope == CirculationScope.CONNECTED_COMPLEX) {
            return List.of(complexRoot);
        }
        if (attached) {
            return rootGround == null ? List.of() : List.of(rootGround);
        }
        return ownGround == null ? List.of() : List.of(ownGround);
    }

    /** Starts for internal storey checks after host-link is proven. */
    private static List<Vec3i> buildLocalEntries(
            CirculationScope scope,
            boolean attached,
            Vec3i rootGround,
            Vec3i ownGround,
            Vec3i complexRoot
    ) {
        if (scope == CirculationScope.CONNECTED_COMPLEX) {
            return List.of(complexRoot);
        }
        List<Vec3i> points = new ArrayList<>();
        if (attached && rootGround != null) {
            points.add(rootGround);
        }
        if (ownGround != null) {
            points.add(ownGround);
        }
        return points;
    }

    private static List<Vec3i> bridgeStartEntries(
            Map<String, MassEntry> byId,
            Component host,
            Map<String, Slot> slotMap,
            Vec3i zero,
            PlannedOccupancy occ,
            Vec3i complexRoot,
            CirculationScope scope
    ) {
        MassEntry hostEntry = byId.get(componentId(host));
        if (hostEntry != null && !hostEntry.hostLinkEntries().isEmpty()) {
            return hostEntry.hostLinkEntries();
        }
        if (scope == CirculationScope.CONNECTED_COMPLEX && complexRoot != null) {
            return List.of(complexRoot);
        }
        int hostY = groundStoreyY(host);
        Vec3i start = shift(
                BridgeWalkability.interiorFloorCell(host, hostY),
                slotAnchorOrZero(slotMap, host.slotId(), zero));
        if (start != null && WalkabilityGraph.canStand(occ, start)) {
            return List.of(start);
        }
        return List.of();
    }

    private static Vec3i firstStandableSample(
            PlannedOccupancy occ,
            Component mass,
            Vec3i ownGroundOrNull,
            Map<String, Slot> slotMap,
            Vec3i zero
    ) {
        if (ownGroundOrNull != null && WalkabilityGraph.canStand(occ, ownGroundOrNull)) {
            return ownGroundOrNull;
        }
        Vec3i massOff = slotAnchorOrZero(slotMap, mass.slotId(), zero);
        ResolvedComponentGeometry geo = ResolvedComponentGeometry.resolve(mass);
        List<Integer> ys = new ArrayList<>();
        if (geo != null && !geo.floorYs().isEmpty()) {
            ys.addAll(geo.floorYs());
        } else {
            int envelope = Math.max(1, mass.dimensions().height());
            ResolvedFloorLayout layout = ResolvedFloorLayout.from(mass, envelope);
            if (!layout.floorYs().isEmpty()) {
                int base = mass.relativePosition() == null ? 0 : mass.relativePosition().y();
                for (int rel : layout.floorYs()) {
                    ys.add(base + rel);
                }
            } else if (mass.relativePosition() != null) {
                ys.add(mass.relativePosition().y());
            }
        }
        for (int y : ys) {
            Vec3i sample = shift(BridgeWalkability.interiorFloorCell(mass, y), massOff);
            if (sample != null && WalkabilityGraph.canStand(occ, sample)) {
                return sample;
            }
        }
        return null;
    }

    private static Optional<CapabilityGap> massJoinsComplexFromRoot(
            PlannedOccupancy occ,
            Vec3i root,
            Component mass,
            Map<String, Slot> slotMap,
            Vec3i zero,
            String precision
    ) {
        int envelope = Math.max(1, mass.dimensions().height());
        ResolvedFloorLayout layout = ResolvedFloorLayout.from(mass, envelope);
        List<Integer> ys = layout.floorYs().isEmpty()
                ? List.of(groundStoreyY(mass))
                : layout.floorYs();
        Vec3i massOff = slotAnchorOrZero(slotMap, mass.slotId(), zero);
        boolean anyLimit = false;
        for (int y : ys) {
            Vec3i sample = shift(BridgeWalkability.interiorFloorCell(mass, y), massOff);
            if (sample == null || !WalkabilityGraph.canStand(occ, sample)) {
                continue;
            }
            var budget = WalkabilityGraph.adaptiveBudget(root, sample, null);
            var outcome = WalkabilityGraph.searchDetailed(occ, root, sample, budget, null);
            if (outcome.result() == WalkabilityGraph.ReachResult.REACHABLE) {
                return Optional.empty();
            }
            if (outcome.result() == WalkabilityGraph.ReachResult.SEARCH_LIMIT) {
                anyLimit = true;
            }
        }
        if (anyLimit) {
            return Optional.of(new CapabilityGap(
                    "E_WALKABILITY_SEARCH_LIMIT",
                    "Building complex join search exhausted its budget (component_id="
                            + componentId(mass) + ", scope=CONNECTED_COMPLEX, precision=" + precision + ")",
                    "components[]",
                    List.of("Link masses with bridges/corridors or use circulation_scope=BUILDING_LOCAL.",
                            "This is not a proof that no path exists — only that search stopped early.")));
        }
        return incomplete(
                "Mass does not join the walkable complex from root ground (component_id="
                        + componentId(mass) + ", scope=CONNECTED_COMPLEX, precision=" + precision + ")",
                "Connect buildings with bridges/stairs/corridors, or set circulation_scope=BUILDING_LOCAL.");
    }

    private static Optional<CapabilityGap> dataMissing(String message) {
        return Optional.of(new CapabilityGap(
                "E_BUILDING_CIRCULATION_DATA_MISSING",
                message,
                "components[]",
                List.of("Ensure the plan compiles host masses and patches before circulation checks.",
                        "Or set design_requirements.circulation=OMITTED if circulation is not required.")));
    }

    private static Optional<CapabilityGap> reachFromAnyGround(
            PlannedOccupancy occ,
            List<Vec3i> grounds,
            Vec3i goal,
            String massId,
            int storeyY,
            String precision,
            CirculationScope scope,
            String incompleteMessage,
            String incompleteHint
    ) {
        boolean anyLimit = false;
        for (Vec3i ground : grounds) {
            var budget = WalkabilityGraph.adaptiveBudget(ground, goal, null);
            var outcome = WalkabilityGraph.searchDetailed(occ, ground, goal, budget, null);
            if (outcome.result() == WalkabilityGraph.ReachResult.REACHABLE) {
                return Optional.empty();
            }
            if (outcome.result() == WalkabilityGraph.ReachResult.SEARCH_LIMIT) {
                anyLimit = true;
            }
        }
        if (anyLimit) {
            return Optional.of(new CapabilityGap(
                    "E_WALKABILITY_SEARCH_LIMIT",
                    "Building circulation search exhausted its budget (component_id="
                            + massId + ", toY=" + storeyY + ", scope=" + scope
                            + ", precision=" + precision + ")",
                    "components[]",
                    List.of("Add vertical circulation or shorten the building footprint.",
                            "This is not a proof that no path exists — only that search stopped early.")));
        }
        return incomplete(incompleteMessage, incompleteHint);
    }

    private static Optional<CapabilityGap> incomplete(String message, String hint) {
        return Optional.of(new CapabilityGap(
                "E_BUILDING_CIRCULATION_INCOMPLETE",
                message,
                "components[]",
                List.of(hint,
                        "Set design_requirements.circulation=OMITTED if intentional non-circulation.",
                        "precision=BLOCKSTATE/MIXED is plan-geometry, not VoxelShape collision.")));
    }

    private static int groundStoreyY(Component mass) {
        int envelope = mass.dimensions() == null ? 1 : Math.max(1, mass.dimensions().height());
        ResolvedFloorLayout layout = ResolvedFloorLayout.from(mass, envelope);
        if (!layout.floorYs().isEmpty()) {
            return layout.floorYs().getFirst();
        }
        return mass.relativePosition() == null ? 0 : mass.relativePosition().y();
    }

    private static String componentId(Component mass) {
        if (mass == null || mass.params() == null) {
            return "unknown";
        }
        Object id = mass.params().get("component_id");
        return id == null ? "unknown" : String.valueOf(id);
    }

    private static String paramString(Map<String, Object> params, String... keys) {
        if (params == null) {
            return null;
        }
        for (String key : keys) {
            Object v = params.get(key);
            if (v != null) {
                String s = String.valueOf(v).trim();
                if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) {
                    return s;
                }
            }
        }
        return null;
    }

    private static Vec3i slotAnchorOrZero(Map<String, Slot> slotMap, String slotId, Vec3i zero) {
        if (slotMap == null || slotId == null) {
            return zero;
        }
        Slot slot = slotMap.get(slotId);
        if (slot == null || slot.anchor() == null) {
            return zero;
        }
        return slot.anchor();
    }

    private static Vec3i shift(Vec3i local, Vec3i offset) {
        if (local == null) {
            return null;
        }
        if (offset == null) {
            return local;
        }
        return new Vec3i(local.x() + offset.x(), local.y() + offset.y(), local.z() + offset.z());
    }
}
