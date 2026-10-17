package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.BridgeWalkability;
import com.formacraft.common.generation.component.util.HostedPlateOccupancy;
import com.formacraft.common.generation.component.util.ResolvedFloorLayout;
import com.formacraft.common.generation.component.util.WalkabilityGraph;
import com.formacraft.common.generation.design.DesignRequirement;
import com.formacraft.common.generation.design.DesignRequirementAccess;
import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * When {@code design_requirements.circulation=REQUIRED}, prove storey and bridge end-to-end
 * reachability on final plan-geometry occupancy. Bridge deck↔interiors remain in
 * {@link ComponentPlanCompiler}'s always-on bridge walkability gate.
 */
final class BuildingCirculationValidator {
    private BuildingCirculationValidator() {}

    static Optional<CapabilityGap> validate(
            LlmPlan plan,
            List<Component> components,
            List<BlockPatch> patches,
            Map<String, Slot> slotMap
    ) {
        if (DesignRequirementAccess.circulation(plan) != DesignRequirement.REQUIRED) {
            return Optional.empty();
        }
        if (components == null || patches == null || patches.isEmpty()) {
            return Optional.empty();
        }
        var occ = BridgeWalkability.occupancyFromPatches(patches);
        String precision = String.valueOf(occ.precision());
        Vec3i zero = new Vec3i(0, 0, 0);

        // Multi-source: any MASS_MAIN ground may seed reachability (upper floors via bridge OK).
        java.util.ArrayList<Vec3i> grounds = new java.util.ArrayList<>();
        for (Component mass : components) {
            if (mass == null || !isMassMain(mass)) {
                continue;
            }
            int envelope = mass.dimensions() == null ? 1 : Math.max(1, mass.dimensions().height());
            ResolvedFloorLayout layout = ResolvedFloorLayout.from(mass, envelope);
            int groundY = layout.floorYs().isEmpty() ? groundStoreyY(mass) : layout.floorYs().getFirst();
            Vec3i ground = shift(
                    BridgeWalkability.interiorFloorCell(mass, groundY),
                    slotAnchorOrZero(slotMap, mass.slotId(), zero));
            if (ground == null || !WalkabilityGraph.canStand(occ, ground)) {
                return incomplete(
                        "Mass ground interior is not a legal standing cell (component_id="
                                + componentId(mass) + ", y=" + groundY + ", precision=" + precision + ")",
                        "Add a solid floor with 2-high clearance at the ground storey interior.");
            }
            grounds.add(ground);
        }

        for (Component mass : components) {
            if (mass == null || !isMassMain(mass)) {
                continue;
            }
            int envelope = mass.dimensions() == null ? 1 : Math.max(1, mass.dimensions().height());
            ResolvedFloorLayout layout = ResolvedFloorLayout.from(mass, envelope);
            if (layout.floorCount() < 2 || layout.floorYs().size() < 2) {
                continue;
            }
            Vec3i massOff = slotAnchorOrZero(slotMap, mass.slotId(), zero);
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
                        occ, grounds, goal, componentId(mass), storeyY, precision);
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
            Vec3i hostOff = slotAnchorOrZero(slotMap, host.get().slotId(), zero);
            Vec3i targetOff = slotAnchorOrZero(slotMap, target.get().slotId(), zero);
            int hostGroundY = groundStoreyY(host.get());
            Vec3i start = shift(BridgeWalkability.interiorFloorCell(host.get(), hostGroundY), hostOff);
            Vec3i goal = shift(BridgeWalkability.interiorFloorCell(target.get(), plateY), targetOff);
            if (start == null || !WalkabilityGraph.canStand(occ, start)) {
                return incomplete(
                        "Bridge host ground interior not standable (host_id=" + hostId
                                + ", y=" + hostGroundY + ", precision=" + precision + ")",
                        "Ensure the host mass ground floor is walkable.");
            }
            if (goal == null || !WalkabilityGraph.canStand(occ, goal)) {
                return incomplete(
                        "Bridge target interior at plate Y not standable (target_host_id=" + targetId
                                + ", y=" + plateY + ", precision=" + precision + ")",
                        "Ensure the target mass has a floor at the bridge connection level.");
            }
            var budget = WalkabilityGraph.adaptiveBudget(start, goal, null);
            var outcome = WalkabilityGraph.searchDetailed(occ, start, goal, budget, null);
            if (outcome.result() == WalkabilityGraph.ReachResult.SEARCH_LIMIT) {
                return Optional.of(new CapabilityGap(
                        "E_WALKABILITY_SEARCH_LIMIT",
                        "Building circulation search exhausted its budget for bridge end-to-end (host_id="
                                + hostId + ", target_host_id=" + targetId + ", precision=" + precision + ")",
                        "components[]",
                        List.of("Add stairs to the bridge storey or simplify the plan.",
                                "This is not a proof that no path exists — only that search stopped early.")));
            }
            if (outcome.result() != WalkabilityGraph.ReachResult.REACHABLE) {
                return incomplete(
                        "Cannot walk from host ground to target at bridge level (host_id=" + hostId
                                + ", target_host_id=" + targetId + ", plateY=" + plateY
                                + ", precision=" + precision + ")",
                        "Add vertical circulation in the host (or both masses) up to the bridge storey.");
            }
        }
        return Optional.empty();
    }

    private static Optional<CapabilityGap> reachFromAnyGround(
            com.formacraft.common.generation.component.util.PlannedOccupancy occ,
            java.util.List<Vec3i> grounds,
            Vec3i goal,
            String massId,
            int storeyY,
            String precision
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
                            + massId + ", toY=" + storeyY + ", precision=" + precision + ")",
                    "components[]",
                    List.of("Add vertical circulation or shorten the building footprint.",
                            "This is not a proof that no path exists — only that search stopped early.")));
        }
        return incomplete(
                "Storey not reachable from any mass ground (component_id=" + massId
                        + ", toY=" + storeyY + ", precision=" + precision + ")",
                "Add stairs or bridge access so each design storey is reachable from a ground interior.");
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

    private static boolean isMassMain(Component mass) {
        if (mass == null || mass.componentType() == null) {
            return false;
        }
        String type = mass.componentType().toUpperCase(Locale.ROOT);
        return "MASS_MAIN".equals(type) || "MAIN_MASS".equals(type);
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
