package com.formacraft.server.edit;

import net.minecraft.util.math.BlockPos;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Server-authorized edit envelope. LLM intent cannot enlarge this scope.
 * Roof: allowedCells = roof index. Window: allowedCells = authorized wall/window cells for this ticket.
 */
public final class BuildingEditScope {
    private final UUID buildingId;
    private final long baseRevision;
    private final BuildingEditOperation operation;
    private final Set<BlockPos> allowedCells;
    private final Set<BlockPos> protectedCells;
    private final Map<BlockPos, String> expectedWorldStates;
    private final BuildingEditTargetPart targetPart;

    public BuildingEditScope(
            UUID buildingId,
            long baseRevision,
            BuildingEditOperation operation,
            Set<BlockPos> allowedCells,
            Set<BlockPos> protectedCells,
            Map<BlockPos, String> expectedWorldStates
    ) {
        this(buildingId, baseRevision, operation, allowedCells, protectedCells, expectedWorldStates,
                BuildingEditTargetPart.none());
    }

    public BuildingEditScope(
            UUID buildingId,
            long baseRevision,
            BuildingEditOperation operation,
            Set<BlockPos> allowedCells,
            Set<BlockPos> protectedCells,
            Map<BlockPos, String> expectedWorldStates,
            BuildingEditTargetPart targetPart
    ) {
        this.buildingId = Objects.requireNonNull(buildingId, "buildingId");
        this.baseRevision = Math.max(0L, baseRevision);
        this.operation = Objects.requireNonNull(operation, "operation");
        this.allowedCells = allowedCells == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(allowedCells));
        this.protectedCells = protectedCells == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(protectedCells));
        this.expectedWorldStates = expectedWorldStates == null
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(expectedWorldStates));
        this.targetPart = targetPart == null ? BuildingEditTargetPart.none() : targetPart;
    }

    public static BuildingEditScope replaceRoofMaterial(
            BuildingInstance building,
            BuildingPartIndex.RoofPart roof,
            Map<BlockPos, String> baselineExpectedStates,
            Set<BlockPos> protectedCells
    ) {
        Objects.requireNonNull(building, "building");
        Objects.requireNonNull(roof, "roof");
        // Baseline = recorded roof states (memory). Live world is compared later at validate/confirm.
        Map<BlockPos, String> expected = new LinkedHashMap<>(roof.recordedStates());
        if (baselineExpectedStates != null) {
            for (var e : baselineExpectedStates.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && roof.cells().contains(e.getKey())) {
                    expected.put(e.getKey(), e.getValue());
                }
            }
        }
        return new BuildingEditScope(
                building.buildingId(),
                building.revision(),
                BuildingEditOperation.REPLACE_ROOF_MATERIAL,
                roof.cells(),
                protectedCells,
                expected,
                BuildingEditTargetPart.none()
        );
    }

    /**
     * Window insert/seal envelope: only the cells this ticket will touch may change.
     * {@code protectedCells} should include roof (and other structural) cells.
     */
    public static BuildingEditScope replaceFbdProgram(
            BuildingInstance building,
            Set<BlockPos> authorizedCells,
            Map<BlockPos, String> expectedLiveStates
    ) {
        Objects.requireNonNull(building, "building");
        return new BuildingEditScope(
                building.buildingId(),
                building.revision(),
                BuildingEditOperation.REPLACE_FBD_PROGRAM,
                authorizedCells,
                Set.of(),
                expectedLiveStates,
                BuildingEditTargetPart.none()
        );
    }

    public static BuildingEditScope windowEdit(
            BuildingInstance building,
            BuildingEditOperation operation,
            Set<BlockPos> authorizedCells,
            Map<BlockPos, String> expectedLiveStates,
            Set<BlockPos> protectedCells
    ) {
        return windowEdit(building, operation, authorizedCells, expectedLiveStates, protectedCells,
                BuildingEditTargetPart.none());
    }

    public static BuildingEditScope windowEdit(
            BuildingInstance building,
            BuildingEditOperation operation,
            Set<BlockPos> authorizedCells,
            Map<BlockPos, String> expectedLiveStates,
            Set<BlockPos> protectedCells,
            BuildingEditTargetPart targetPart
    ) {
        Objects.requireNonNull(building, "building");
        if (operation == null || !operation.isWindowOp()) {
            throw new IllegalArgumentException("operation must be INSERT_WINDOW or SEAL_WINDOW");
        }
        return new BuildingEditScope(
                building.buildingId(),
                building.revision(),
                operation,
                authorizedCells,
                protectedCells,
                expectedLiveStates,
                targetPart
        );
    }

    public UUID buildingId() { return buildingId; }
    public long baseRevision() { return baseRevision; }
    public BuildingEditOperation operation() { return operation; }
    public Set<BlockPos> allowedCells() { return allowedCells; }
    public Set<BlockPos> protectedCells() { return protectedCells; }
    public Map<BlockPos, String> expectedWorldStates() { return expectedWorldStates; }
    public BuildingEditTargetPart targetPart() { return targetPart; }

    public boolean allows(BlockPos worldPos) {
        return worldPos != null && allowedCells.contains(worldPos);
    }
}
