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
 * Phase 1: allowedCells = roof cells; protectedCells = non-roof cells that must not change.
 */
public final class BuildingEditScope {
    private final UUID buildingId;
    private final long baseRevision;
    private final BuildingEditOperation operation;
    private final Set<BlockPos> allowedCells;
    private final Set<BlockPos> protectedCells;
    private final Map<BlockPos, String> expectedWorldStates;

    public BuildingEditScope(
            UUID buildingId,
            long baseRevision,
            BuildingEditOperation operation,
            Set<BlockPos> allowedCells,
            Set<BlockPos> protectedCells,
            Map<BlockPos, String> expectedWorldStates
    ) {
        this.buildingId = Objects.requireNonNull(buildingId, "buildingId");
        this.baseRevision = Math.max(0L, baseRevision);
        this.operation = Objects.requireNonNull(operation, "operation");
        this.allowedCells = allowedCells == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(allowedCells));
        this.protectedCells = protectedCells == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(protectedCells));
        this.expectedWorldStates = expectedWorldStates == null
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(expectedWorldStates));
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
                expected
        );
    }

    public UUID buildingId() { return buildingId; }
    public long baseRevision() { return baseRevision; }
    public BuildingEditOperation operation() { return operation; }
    public Set<BlockPos> allowedCells() { return allowedCells; }
    public Set<BlockPos> protectedCells() { return protectedCells; }
    public Map<BlockPos, String> expectedWorldStates() { return expectedWorldStates; }

    public boolean allows(BlockPos worldPos) {
        return worldPos != null && allowedCells.contains(worldPos);
    }
}
