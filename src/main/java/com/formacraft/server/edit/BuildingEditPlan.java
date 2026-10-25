package com.formacraft.server.edit;

import java.util.Objects;
import java.util.UUID;

/** Semantic edit intent produced after server target binding (not a world write grant). */
public final class BuildingEditPlan {
    private final UUID targetBuildingId;
    private final BuildingEditOperation operation;
    private final String materialIntent;
    private final String constraintsNote;

    public BuildingEditPlan(
            UUID targetBuildingId,
            BuildingEditOperation operation,
            String materialIntent,
            String constraintsNote
    ) {
        this.targetBuildingId = Objects.requireNonNull(targetBuildingId, "targetBuildingId");
        this.operation = Objects.requireNonNull(operation, "operation");
        this.materialIntent = materialIntent == null ? "" : materialIntent;
        this.constraintsNote = constraintsNote == null ? "" : constraintsNote;
    }

    public UUID targetBuildingId() { return targetBuildingId; }
    public BuildingEditOperation operation() { return operation; }
    public String materialIntent() { return materialIntent; }
    public String constraintsNote() { return constraintsNote; }
}
