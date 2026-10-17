package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.BridgeWalkability;
import com.formacraft.common.generation.component.util.HostedPlateOccupancy;
import com.formacraft.common.generation.component.util.MassComponentKinds;
import com.formacraft.common.generation.component.util.PlannedOccupancy;
import com.formacraft.common.generation.component.util.ResolvedComponentGeometry;
import com.formacraft.common.generation.component.util.ResolvedFloorLayout;
import com.formacraft.common.generation.component.util.StoreyWalkRegions;
import com.formacraft.common.generation.component.util.WalkabilityGraph;
import com.formacraft.common.generation.design.CirculationHostLinks;
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
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
        Optional<CapabilityGap> hostLinksGap = DesignRequirementAccess.validateCirculationHostLinks(plan);
        if (hostLinksGap.isPresent()) {
            return hostLinksGap;
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
        CirculationHostLinks hostLinks = DesignRequirementAccess.circulationHostLinks(plan);
        var occ = BridgeWalkability.occupancyFromPatches(patches);
        String precision = String.valueOf(occ.precision());
        Vec3i zero = new Vec3i(0, 0, 0);

        // First pass: internal ground starts (region reps) + complex root (independent only).
        // This is an internal circulation start — not proof of exterior doorway access.
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
            InternalGroundStart resolved = resolveInternalGroundStart(
                    occ, mass, slotMap, zero, precision);
            if (resolved.gap().isPresent()) {
                return resolved.gap();
            }
            Vec3i ownGround = resolved.startOrNull();
            if (ownGround != null) {
                ownGroundByMass.put(mass, ownGround);
            }
            if (!attached && ownGround == null) {
                int groundY = absoluteFloorYs(mass).getFirst();
                return incomplete(
                        "No standable ground region for internal circulation start (component_id="
                                + componentId(mass) + ", y=" + groundY + ", precision=" + precision + ")",
                        "Ensure the ground storey has a standable interior region (courtyard rings OK). "
                                + "This does not verify exterior doorways. "
                                + "Or set host_id if this is an attached aerial mass.");
            }
            if (!attached && ownGround != null && complexRoot == null) {
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
                    InternalGroundStart rootStart = resolveInternalGroundStart(
                            occ, root, slotMap, zero, precision);
                    if (rootStart.gap().isPresent()) {
                        return rootStart.gap();
                    }
                    rootGround = rootStart.startOrNull();
                }
                if (rootGround == null || !WalkabilityGraph.canStand(occ, rootGround)) {
                    return incomplete(
                            "Attached mass root has no standable ground region (component_id="
                                    + componentId(mass) + ", root_id=" + componentId(root)
                                    + ", precision=" + precision + ")",
                            "Ensure the independent host mass has a walkable ground interior region.");
                }
            }
            List<Vec3i> hostLink = buildHostLinkEntries(scope, attached, rootGround, ownGround, complexRoot);
            List<Vec3i> local = buildLocalEntries(scope, attached, rootGround, ownGround, complexRoot);
            MassEntry entry = new MassEntry(
                    mass, ownGround, directHost, rootGround, attached, hostLink, local);
            entries.add(entry);
            byId.put(componentId(mass), entry);
        }

        // DIRECT first: each parent→child edge from parent's own cells → child regions
        if (hostLinks == CirculationHostLinks.DIRECT) {
            for (MassEntry entry : entries) {
                if (!entry.attached() || entry.hostOrNull() == null) {
                    continue;
                }
                MassEntry parentEntry = byId.get(componentId(entry.hostOrNull()));
                if (parentEntry == null) {
                    continue;
                }
                var startsOrGap = directParentStarts(occ, parentEntry, slotMap, zero, precision);
                if (startsOrGap.gap().isPresent()) {
                    return startsOrGap.gap();
                }
                List<Vec3i> starts = startsOrGap.goals();
                if (starts.isEmpty()) {
                    return hostLinkBlocked(
                            componentId(parentEntry.mass()), componentId(entry.mass()), precision,
                            "Direct host has no standable entry");
                }
                var childOrGap = allStandableRegionGoals(occ, entry.mass(), slotMap, zero, precision);
                if (childOrGap.gap().isPresent()) {
                    return childOrGap.gap();
                }
                List<Vec3i> childGoals = childOrGap.goals();
                if (childGoals.isEmpty()) {
                    return hostLinkBlocked(
                            componentId(parentEntry.mass()), componentId(entry.mass()), precision,
                            "Attached child has no standable region");
                }
                for (Vec3i goal : childGoals) {
                    Optional<CapabilityGap> edgeGap = reachFromAnyGround(
                            occ, starts, goal, componentId(entry.mass()), goal.y(), precision, scope,
                            "Direct host link blocked (parent=" + componentId(parentEntry.mass())
                                    + ", child=" + componentId(entry.mass())
                                    + ", toY=" + goal.y() + ", precision=" + precision + ")",
                            "Connect the child mass to its direct host_id with a bridge, corridor, or stairs.");
                    if (edgeGap.isPresent()) {
                        CapabilityGap g = edgeGap.get();
                        if ("E_WALKABILITY_SEARCH_LIMIT".equals(g.code())) {
                            return edgeGap;
                        }
                        return hostLinkBlocked(
                                componentId(parentEntry.mass()), componentId(entry.mass()), precision,
                                g.message());
                    }
                }
            }
        }

        // Attached: prove host-root → every standable region (never start from ownGround)
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
            var goalsOrGap = allStandableRegionGoals(occ, entry.mass(), slotMap, zero, precision);
            if (goalsOrGap.gap().isPresent()) {
                return goalsOrGap.gap();
            }
            List<Vec3i> goals = goalsOrGap.goals();
            if (goals.isEmpty()) {
                return incomplete(
                        "Attached mass has no standable interior sample (component_id="
                                + componentId(entry.mass()) + ", precision=" + precision + ")",
                        "Ensure the attached mass has a solid floor with clearance at an interior cell.");
            }
            Optional<CapabilityGap> attachedGap = reachAllGoals(
                    occ, entry.hostLinkEntries(), goals, componentId(entry.mass()), precision, scope,
                    "Attached mass not reachable from host entry (component_id="
                            + componentId(entry.mass())
                            + ", scope=" + scope + ", precision=" + precision + ")",
                    "Connect the attached mass with a bridge, corridor, or stairs from the host entry.");
            if (attachedGap.isPresent()) {
                return attachedGap;
            }
        }

        // Independent masses: every standable region on every design storey (incl. ground).
        // BUILDING_LOCAL starts at ownGround; CONNECTED_COMPLEX at complexRoot (join may be via
        // bridge level — region reps from StoreyWalkRegions prove complex membership).
        for (MassEntry entry : entries) {
            if (entry.attached()) {
                continue;
            }
            Component mass = entry.mass();
            List<Vec3i> starts = entry.localEntries();
            if (starts.isEmpty()) {
                return incomplete(
                        "No circulation entry for independent mass (component_id=" + componentId(mass)
                                + ", precision=" + precision + ")",
                        "Provide a standable ground interior for this mass.");
            }
            var goalsOrGap = allStandableRegionGoals(occ, mass, slotMap, zero, precision);
            if (goalsOrGap.gap().isPresent()) {
                return goalsOrGap.gap();
            }
            List<Vec3i> goals = goalsOrGap.goals();
            if (goals.isEmpty()) {
                return incomplete(
                        "Independent mass has no standable region (component_id="
                                + componentId(mass) + ", precision=" + precision + ")",
                        "Ensure each design storey has a solid floor and clearance at interior cells.");
            }
            Optional<CapabilityGap> regionGap = reachAllGoals(
                    occ, starts, goals, componentId(mass), precision, scope,
                    "Standable region not reachable from mass entry (component_id="
                            + componentId(mass) + ", scope=" + scope + ", precision=" + precision + ")",
                    scope == CirculationScope.BUILDING_LOCAL
                            ? "Connect interior rooms with openings/corridors/stairs, or set circulation=OMITTED."
                            : "Join each standable region to the complex network with stairs or bridges.");
            if (regionGap.isPresent()) {
                return regionGap;
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
            Vec3i plateOff = slotAnchorOrZero(slotMap, plate.slotId(), zero);
            BridgeWalkability.StoreyGoals targetGoals = BridgeWalkability.storeyGoalsPreferringInterior(
                    occ, target.get(), plateY, targetOff);
            if (targetGoals.scanLimited()) {
                return regionScanLimit(targetId, plateY, targetGoals.areaCells(), precision);
            }
            if (targetGoals.isEmpty()) {
                return incomplete(
                        "Bridge target has no standable storey region at plate Y (target_host_id="
                                + targetId + ", y=" + plateY + ", precision=" + precision + ")",
                        "Ensure the target mass has a standable floor region at the bridge connection level.");
            }
            Vec3i deck = shift(BridgeWalkability.deckFloorCell(plate), plateOff);
            if (deck == null || !WalkabilityGraph.canStand(occ, deck)) {
                return incomplete(
                        "Bridge deck is not a legal standing cell (host_id=" + hostId
                                + ", target_host_id=" + targetId + ", plateY=" + plateY
                                + ", precision=" + precision + ")",
                        "Ensure the bridge plate has a solid walkable deck.");
            }
            Optional<CapabilityGap> deckJoin = reachAnyGoal(
                    occ, List.of(deck), targetGoals.goals(), hostId, plateY, precision, scope,
                    "Bridge deck does not join target storey region (host_id=" + hostId
                            + ", target_host_id=" + targetId + ", plateY=" + plateY
                            + ", precision=" + precision + ")",
                    "Connect the bridge deck into the target mass interior at the connection level.");
            if (deckJoin.isPresent()) {
                return rewriteBridgeSearchLimit(deckJoin.get(), hostId, targetId, precision);
            }
            List<Vec3i> startCandidates = bridgeStartEntries(byId, host.get(), complexRoot, scope);
            if (startCandidates.isEmpty()) {
                return incomplete(
                        "Bridge host has no circulation entry (host_id=" + hostId
                                + ", precision=" + precision + ")",
                        "Ensure the bridge host (or its independent root) has a walkable ground.");
            }
            Optional<CapabilityGap> bridgeGap = reachAnyGoal(
                    occ, startCandidates, targetGoals.goals(), hostId, plateY, precision, scope,
                    "Cannot walk from host entry to target at bridge level (host_id=" + hostId
                            + ", target_host_id=" + targetId + ", plateY=" + plateY
                            + ", precision=" + precision + ")",
                    "Add vertical circulation in the host (or both masses) up to the bridge storey.");
            if (bridgeGap.isPresent()) {
                return rewriteBridgeSearchLimit(bridgeGap.get(), hostId, targetId, precision);
            }
        }
        return Optional.empty();
    }

    private static Optional<CapabilityGap> rewriteBridgeSearchLimit(
            CapabilityGap gap, String hostId, String targetId, String precision
    ) {
        if (!"E_WALKABILITY_SEARCH_LIMIT".equals(gap.code())) {
            return Optional.of(gap);
        }
        return Optional.of(new CapabilityGap(
                "E_WALKABILITY_SEARCH_LIMIT",
                "Building circulation search exhausted its budget for bridge end-to-end (host_id="
                        + hostId + ", target_host_id=" + targetId + ", precision=" + precision + ")",
                "components[]",
                List.of("Add stairs to the bridge storey or simplify the plan.",
                        "This is not a proof that no path exists — only that search stopped early.")));
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

    /** Resolved circulation entries only — no fixed interiorFloorCell fallback. */
    private static List<Vec3i> bridgeStartEntries(
            Map<String, MassEntry> byId,
            Component host,
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
        return List.of();
    }

    private static List<Integer> absoluteFloorYs(Component mass) {
        ResolvedComponentGeometry geo = ResolvedComponentGeometry.resolve(mass);
        if (geo != null && !geo.floorYs().isEmpty()) {
            return geo.floorYs();
        }
        int envelope = mass.dimensions() == null ? 1 : Math.max(1, mass.dimensions().height());
        ResolvedFloorLayout layout = ResolvedFloorLayout.from(mass, envelope);
        int base = mass.relativePosition() == null ? 0 : mass.relativePosition().y();
        if (!layout.floorYs().isEmpty()) {
            List<Integer> ys = new ArrayList<>();
            for (int rel : layout.floorYs()) {
                ys.add(base + rel);
            }
            return ys;
        }
        return List.of(base);
    }

    private record RegionGoalSet(List<Vec3i> goals, Optional<CapabilityGap> gap) {}

    /**
     * Internal circulation start on the ground storey from {@link StoreyWalkRegions} reps.
     * Prefer a standable {@link BridgeWalkability#interiorFloorCell} when present for stability;
     * otherwise the first region representative. Not an exterior doorway proof.
     */
    private record InternalGroundStart(Vec3i startOrNull, Optional<CapabilityGap> gap) {}

    private static InternalGroundStart resolveInternalGroundStart(
            PlannedOccupancy occ,
            Component mass,
            Map<String, Slot> slotMap,
            Vec3i zero,
            String precision
    ) {
        List<Integer> ys = absoluteFloorYs(mass);
        int groundAbsY = ys.getFirst();
        RegionGoalSet atY = regionGoalsAtY(occ, mass, groundAbsY, slotMap, zero, precision);
        if (atY.gap().isPresent()) {
            return new InternalGroundStart(null, atY.gap());
        }
        List<Vec3i> reps = atY.goals();
        if (reps.isEmpty()) {
            return new InternalGroundStart(null, Optional.empty());
        }
        Vec3i preferred = shift(
                BridgeWalkability.interiorFloorCell(mass, groundAbsY),
                slotAnchorOrZero(slotMap, mass.slotId(), zero));
        if (preferred != null && WalkabilityGraph.canStand(occ, preferred)) {
            return new InternalGroundStart(preferred, Optional.empty());
        }
        return new InternalGroundStart(reps.getFirst(), Optional.empty());
    }

    private static RegionGoalSet regionGoalsAtY(
            PlannedOccupancy occ,
            Component mass,
            int localStoreyY,
            Map<String, Slot> slotMap,
            Vec3i zero,
            String precision
    ) {
        Vec3i massOff = slotAnchorOrZero(slotMap, mass.slotId(), zero);
        StoreyWalkRegions.ScanResult scan =
                StoreyWalkRegions.scan(occ, mass, localStoreyY, massOff);
        if (scan.status() == StoreyWalkRegions.Status.SCAN_LIMIT) {
            return new RegionGoalSet(List.of(), regionScanLimit(
                    componentId(mass), localStoreyY, scan.areaCells(), precision));
        }
        return new RegionGoalSet(scan.representatives(), Optional.empty());
    }

    private static RegionGoalSet allStandableRegionGoals(
            PlannedOccupancy occ,
            Component mass,
            Map<String, Slot> slotMap,
            Vec3i zero,
            String precision
    ) {
        Set<Vec3i> goals = new LinkedHashSet<>();
        for (int y : absoluteFloorYs(mass)) {
            RegionGoalSet atY = regionGoalsAtY(occ, mass, y, slotMap, zero, precision);
            if (atY.gap().isPresent()) {
                return atY;
            }
            goals.addAll(atY.goals());
        }
        return new RegionGoalSet(new ArrayList<>(goals), Optional.empty());
    }

    /**
     * DIRECT edge starts from the parent's own standable cells only (not the independent root),
     * so a root↔child detour cannot substitute for parent↔child.
     */
    private static RegionGoalSet directParentStarts(
            PlannedOccupancy occ,
            MassEntry parent,
            Map<String, Slot> slotMap,
            Vec3i zero,
            String precision
    ) {
        Set<Vec3i> starts = new LinkedHashSet<>();
        if (parent.ownGroundOrNull() != null) {
            starts.add(parent.ownGroundOrNull());
        }
        RegionGoalSet regions = allStandableRegionGoals(occ, parent.mass(), slotMap, zero, precision);
        if (regions.gap().isPresent()) {
            return regions;
        }
        starts.addAll(regions.goals());
        return new RegionGoalSet(new ArrayList<>(starts), Optional.empty());
    }

    private static Optional<CapabilityGap> regionScanLimit(
            String massId, int storeyY, long area, String precision
    ) {
        return Optional.of(new CapabilityGap(
                "E_CIRCULATION_REGION_SCAN_LIMIT",
                "Storey region scan exceeds budget (component_id=" + massId
                        + ", storeyY=" + storeyY + ", area=" + area
                        + ", max=" + StoreyWalkRegions.MAX_CELLS
                        + ", precision=" + precision + ")",
                "components[]",
                List.of("Reduce mass footprint or split the building.",
                        "This is not a proof that walkable regions are connected — scan stopped by budget.")));
    }

    private static Optional<CapabilityGap> reachAllGoals(
            PlannedOccupancy occ,
            List<Vec3i> starts,
            List<Vec3i> goals,
            String massId,
            String precision,
            CirculationScope scope,
            String incompleteMessage,
            String incompleteHint
    ) {
        for (Vec3i goal : goals) {
            String messageWithY = incompleteMessage.contains("toY=")
                    ? incompleteMessage
                    : incompleteMessage.replace(
                            ", precision=" + precision + ")",
                            ", toY=" + goal.y() + ", precision=" + precision + ")");
            Optional<CapabilityGap> gap = reachFromAnyGround(
                    occ, starts, goal, massId, goal.y(), precision, scope,
                    messageWithY, incompleteHint);
            if (gap.isPresent()) {
                return gap;
            }
        }
        return Optional.empty();
    }

    /** Succeeds if any goal is reachable from any start; otherwise returns the last incomplete gap. */
    private static Optional<CapabilityGap> reachAnyGoal(
            PlannedOccupancy occ,
            List<Vec3i> starts,
            List<Vec3i> goals,
            String massId,
            int storeyY,
            String precision,
            CirculationScope scope,
            String incompleteMessage,
            String incompleteHint
    ) {
        Optional<CapabilityGap> last = Optional.empty();
        for (Vec3i goal : goals) {
            Optional<CapabilityGap> gap = reachFromAnyGround(
                    occ, starts, goal, massId, storeyY, precision, scope,
                    incompleteMessage, incompleteHint);
            if (gap.isEmpty()) {
                return Optional.empty();
            }
            last = gap;
            if ("E_WALKABILITY_SEARCH_LIMIT".equals(gap.get().code())) {
                return gap;
            }
        }
        return last;
    }

    private static Optional<CapabilityGap> hostLinkBlocked(String parentId, String childId, String precision, String detail) {
        return Optional.of(new CapabilityGap(
                "E_CIRCULATION_HOST_LINK_BLOCKED",
                "Direct host link blocked (parent=" + parentId + ", child=" + childId
                        + ", precision=" + precision + "): " + detail,
                "components[].params.host_id",
                List.of("Connect child to its direct host_id with a bridge, corridor, or stairs.",
                        "Or set circulation_host_links=ROOT if only root reachability is required.")));
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
