package com.formacraft.server.edit;

import com.formacraft.common.component.transform.BlockStateStringUtil;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.memory.MemoryManager;
import com.formacraft.server.memory.ProjectMemory;
import com.formacraft.server.preview.PreviewEditKind;
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
 * Building-edit tickets never degrade to ordinary patches when Pending is missing.
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
     *
     * @param editKind stamped on the PreviewTicket at issue time (immutable)
     */
    public static Outcome validateBeforeApply(
            MemoryManager memoryManager,
            ServerWorld world,
            UUID playerId,
            Identifier dimension,
            BlockPos origin,
            List<BlockPatch> patches,
            PreviewEditKind editKind,
            BuildingEditPendingRegistry.Pending pending
    ) {
        PreviewEditKind kind = editKind == null ? PreviewEditKind.NONE : editKind;
        if (!kind.requiresBuildingEditPending()) {
            return Outcome.pass(null);
        }
        if (pending == null) {
            return Outcome.reject("building_edit_pending_missing");
        }
        if (playerId == null || !playerId.equals(pending.playerId())) {
            return Outcome.reject("building_edit_player_mismatch");
        }
        if (dimension == null || !dimension.equals(pending.dimension())) {
            return Outcome.reject("building_edit_dimension_mismatch");
        }
        BuildingEditOperation expectedOp = kind.toOperation();
        if (expectedOp != null && pending.operation() != expectedOp) {
            return Outcome.reject("building_edit_operation_mismatch");
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
        if (BuildingMemoryKeys.isMemorySyncBlocked(memory)) {
            return Outcome.reject("building_edit_memory_sync_blocked");
        }
        long currentRevision = BuildingMemoryKeys.revisionOf(memory);
        if (currentRevision != pending.baseRevision()) {
            return Outcome.reject("building_edit_revision_mismatch expected="
                    + pending.baseRevision() + " actual=" + currentRevision);
        }

        BuildingPartIndex index = BuildingPartIndex.fromMemory(memory);
        if (pending.operation() == BuildingEditOperation.REPLACE_ROOF_MATERIAL) {
            if (index.roof().isEmpty()) {
                return Outcome.reject("building_edit_roof_index_missing");
            }
            if (!Objects.equals(index.roof().cells(), pending.allowedCells())) {
                return Outcome.reject("building_edit_roof_cells_drift");
            }
        } else if (pending.operation().isFbdReplace()) {
            if (!BuildingMemoryKeys.hasFbdSourcePlan(memory)) {
                return Outcome.reject("building_edit_fbd_source_missing");
            }
            if (pending.allowedCells().isEmpty()) {
                return Outcome.reject("building_edit_fbd_cells_missing");
            }
            if (BuildingEditPendingRegistry.peekFbdSourcePlan(
                    pending.ticketId()) == null) {
                return Outcome.reject("building_edit_fbd_plan_missing");
            }
            BlockPos originPos = origin == null ? BlockPos.ORIGIN : origin;
            for (BlockPatch patch : patches) {
                if (patch == null) continue;
                BlockPos writeCell = originPos.add(patch.dx(), patch.dy(), patch.dz()).toImmutable();
                if (!pending.allowedCells().contains(writeCell)) {
                    return Outcome.reject("building_edit_fbd_unauthorized@" + writeCell.toShortString());
                }
            }
        } else if (pending.operation().isWindowOp()) {
            if (pending.allowedCells().isEmpty()) {
                return Outcome.reject("building_edit_window_cells_missing");
            }
            // Roof cells must stay protected: ticket allowed set must not claim them.
            for (BlockPos roofCell : index.roof().cells()) {
                if (pending.allowedCells().contains(roofCell)) {
                    return Outcome.reject("building_edit_window_overlaps_roof@" + roofCell.toShortString());
                }
            }
            // Neighbor isolation: authorized window cells must stay inside the building AABB.
            for (BlockPos cell : pending.allowedCells()) {
                if (cell != null && !instance.contains(cell)) {
                    return Outcome.reject("building_edit_window_outside_building@" + cell.toShortString());
                }
            }
            // Every ticket patch write must still lie in the frozen authorized envelope.
            BlockPos originPos = origin == null ? BlockPos.ORIGIN : origin;
            for (BlockPatch patch : patches) {
                if (patch == null) continue;
                BlockPos writeCell = originPos.add(patch.dx(), patch.dy(), patch.dz()).toImmutable();
                if (!pending.allowedCells().contains(writeCell)) {
                    return Outcome.reject("building_edit_patch_outside_authorized@" + writeCell.toShortString());
                }
            }
            Outcome identity = validateTargetPartIdentity(pending, index);
            if (!identity.ok()) return identity;
        }

        Map<BlockPos, String> live = captureLive(world, pending.allowedCells());
        BuildingEditScope scope = pending.toScope();
        // Re-attach roof as protected for window ops at confirm time.
        if (pending.operation().isWindowOp() && !index.roof().isEmpty()) {
            scope = new BuildingEditScope(
                    scope.buildingId(),
                    scope.baseRevision(),
                    scope.operation(),
                    scope.allowedCells(),
                    index.roof().cells(),
                    scope.expectedWorldStates(),
                    scope.targetPart());
        }
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

    /**
     * Reject tickets whose frozen wall/window identity no longer matches Memory membership.
     * Same coordinates under a different partId must not inherit the old authorization.
     */
    static Outcome validateTargetPartIdentity(
            BuildingEditPendingRegistry.Pending pending,
            BuildingPartIndex index
    ) {
        BuildingEditTargetPart part = pending.targetPart();
        if (part == null || !part.isPresent()) {
            return Outcome.reject("building_edit_target_part_missing");
        }
        // Digest must still describe the frozen expected envelope for this part id.
        String frozen = part.digestWith(pending.allowedCells(), pending.expectedWorldStates());
        if (!frozen.equals(part.identityDigest())) {
            return Outcome.reject("building_edit_target_part_digest_mismatch");
        }
        if (pending.operation() == BuildingEditOperation.SEAL_WINDOW) {
            BuildingPartIndex.WindowOpening opening = findOpening(index, part.targetPartId());
            if (opening != null) {
                if (!Objects.equals(opening.hostWallId(), part.hostWallId())
                        || opening.floorIndex() != part.floorIndex()
                        || !opening.facing().equalsIgnoreCase(part.facing())) {
                    return Outcome.reject("building_edit_window_identity_drift@" + part.targetPartId());
                }
            }
            // Another opening claiming the same cells under a different id → refuse.
            for (BuildingPartIndex.WindowOpening other : index.windows().openings()) {
                if (other == null || other.windowId().equals(part.targetPartId())) continue;
                for (BlockPos cell : pending.allowedCells()) {
                    if (other.cells().contains(cell)) {
                        return Outcome.reject("building_edit_window_identity_replaced@" + cell.toShortString());
                    }
                }
            }
        } else if (pending.operation() == BuildingEditOperation.INSERT_WINDOW) {
            // Host wall face, when indexed, must still match; conflicting openings cannot own the cells.
            BuildingPartIndex.WallFace face = findWallFace(index, part.targetPartId());
            if (face != null) {
                if (face.floorIndex() != part.floorIndex()
                        || !face.facing().equalsIgnoreCase(part.facing())) {
                    return Outcome.reject("building_edit_wall_identity_drift@" + part.targetPartId());
                }
            }
            for (BuildingPartIndex.WindowOpening other : index.windows().openings()) {
                if (other == null) continue;
                for (BlockPos cell : pending.expectedWorldStates().keySet()) {
                    if (other.cells().contains(cell)) {
                        return Outcome.reject("building_edit_wall_cells_owned_by_window@" + cell.toShortString());
                    }
                }
            }
        }
        return Outcome.pass(pending);
    }

    private static BuildingPartIndex.WindowOpening findOpening(BuildingPartIndex index, String windowId) {
        if (index == null || windowId == null || windowId.isBlank()) return null;
        for (BuildingPartIndex.WindowOpening opening : index.windows().openings()) {
            if (opening != null && windowId.equals(opening.windowId())) return opening;
        }
        return null;
    }

    private static BuildingPartIndex.WallFace findWallFace(BuildingPartIndex index, String wallId) {
        if (index == null || wallId == null || wallId.isBlank()) return null;
        for (BuildingPartIndex.WallFace face : index.walls().faces()) {
            if (face != null && wallId.equals(face.wallId())) return face;
        }
        return null;
    }
}
