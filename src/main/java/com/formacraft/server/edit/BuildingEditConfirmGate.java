package com.formacraft.server.edit;

import com.formacraft.common.component.transform.BlockStateStringUtil;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.memory.MemoryManager;
import com.formacraft.server.memory.ProjectMemory;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Pre-apply confirm gate for building-aware edits.
 * Must run before world writes; failure rejects Apply entirely.
 */
public final class BuildingEditConfirmGate {
    public record Outcome(boolean ok, String message, BuildingEditPendingRegistry.Pending pending) {
        public static Outcome pass(BuildingEditPendingRegistry.Pending pending) {
            return new Outcome(true, null, pending);
        }

        public static Outcome reject(String message) {
            return new Outcome(false, message == null ? "building_edit_confirm_rejected" : message, null);
        }
    }

    private BuildingEditConfirmGate() {}

    /**
     * Validate a pending building edit against current Memory + live world + ticket patches.
     * Does not consume the pending entry.
     */
    public static Outcome validateBeforeApply(
            MemoryManager memoryManager,
            ServerWorld world,
            UUID playerId,
            Identifier dimension,
            BlockPos origin,
            List<BlockPatch> patches,
            BuildingEditPendingRegistry.Pending pending
    ) {
        if (pending == null) {
            return Outcome.pass(null); // ordinary non-building-edit ticket
        }
        if (playerId == null || !playerId.equals(pending.playerId())) {
            return Outcome.reject("building_edit_player_mismatch");
        }
        if (dimension == null || !dimension.equals(pending.dimension())) {
            return Outcome.reject("building_edit_dimension_mismatch");
        }
        if (memoryManager == null) {
            return Outcome.reject("building_edit_memory_unavailable");
        }
        ProjectMemory memory = memoryManager.getMemory(pending.buildingId().toString());
        BuildingInstance instance = BuildingInstance.fromMemory(memory);
        if (instance == null) {
            return Outcome.reject("building_edit_not_registered");
        }
        if (!instance.dimension().equals(pending.dimension())) {
            return Outcome.reject("building_edit_dimension_mismatch");
        }
        long currentRevision = BuildingMemoryKeys.revisionOf(memory);
        if (currentRevision != pending.baseRevision()) {
            return Outcome.reject("building_edit_revision_mismatch expected="
                    + pending.baseRevision() + " actual=" + currentRevision);
        }

        BuildingPartIndex index = BuildingPartIndex.fromMemory(memory);
        if (index.roof().isEmpty()) {
            return Outcome.reject("building_edit_roof_index_missing");
        }
        if (!Objects.equals(index.roof().cells(), pending.allowedCells())) {
            return Outcome.reject("building_edit_roof_cells_drift");
        }

        Map<BlockPos, String> live = captureLive(world, pending.allowedCells());
        BuildingEditScope scope = pending.toScope();
        BuildingEditValidation validation = BuildingEditScopeValidator.validate(
                scope, origin, patches, currentRevision, live);
        if (!validation.accepted()) {
            String detail = validation.conflicts().isEmpty()
                    ? "building_edit_scope_rejected"
                    : String.join("; ", validation.conflicts());
            return Outcome.reject(detail);
        }
        return Outcome.pass(pending);
    }

    public static Map<BlockPos, String> captureLive(ServerWorld world, Set<BlockPos> cells) {
        Map<BlockPos, String> live = new LinkedHashMap<>();
        if (world == null || cells == null) return live;
        for (BlockPos cell : cells) {
            if (cell == null) continue;
            BlockState state = world.getBlockState(cell);
            live.put(cell.toImmutable(), BlockStateStringUtil.fromState(state));
        }
        return live;
    }
}
