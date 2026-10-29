package com.formacraft.server.edit;

import com.formacraft.common.component.transform.BlockStateStringUtil;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.BlockPatchTargetResolver;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Independent of the LLM: every effective write must land in allowedCells,
 * and protected / expected / live states must not be violated (fail-closed).
 * Operation-specific rules: roof forbids geometry holes; windows may open/seal within scope.
 */
public final class BuildingEditScopeValidator {
    private BuildingEditScopeValidator() {}

    public static BuildingEditValidation validate(
            BuildingEditScope scope,
            BlockPos origin,
            List<BlockPatch> patches,
            long currentRevision,
            Map<BlockPos, String> liveWorldStates
    ) {
        Objects.requireNonNull(scope, "scope");
        List<String> conflicts = new ArrayList<>();
        List<BlockPos> unexpected = new ArrayList<>();

        BuildingEditOperation op = scope.operation();
        if (op != BuildingEditOperation.REPLACE_ROOF_MATERIAL
                && op != BuildingEditOperation.INSERT_WINDOW
                && op != BuildingEditOperation.SEAL_WINDOW
                && op != BuildingEditOperation.REPLACE_FBD_PROGRAM) {
            conflicts.add("unsupported_operation:" + op);
            return BuildingEditValidation.rejected(conflicts, unexpected);
        }
        if (currentRevision != scope.baseRevision()) {
            conflicts.add("revision_mismatch expected=" + scope.baseRevision() + " actual=" + currentRevision);
            return BuildingEditValidation.rejected(conflicts, unexpected);
        }
        if (scope.allowedCells().isEmpty()) {
            String emptyCode = op.isWindowOp() ? "window_cells_unavailable"
                    : (op.isFbdReplace() ? "fbd_cells_unavailable" : "roof_cells_unavailable");
            conflicts.add(emptyCode);
            return BuildingEditValidation.rejected(conflicts, unexpected);
        }
        if (patches == null || patches.isEmpty()) {
            conflicts.add("empty_patch");
            return BuildingEditValidation.rejected(conflicts, unexpected);
        }
        if (liveWorldStates == null) {
            conflicts.add("live_world_states_missing");
            return BuildingEditValidation.rejected(conflicts, unexpected);
        }

        BlockPos originPos = origin == null ? BlockPos.ORIGIN : origin;
        boolean anyChange = false;
        for (BlockPatch patch : patches) {
            if (patch == null) continue;
            BlockPos world = originPos.add(patch.dx(), patch.dy(), patch.dz()).toImmutable();
            if (!scope.allows(world)) {
                unexpected.add(world);
                conflicts.add("unauthorized_write@" + world.toShortString());
                continue;
            }
            if (scope.protectedCells().contains(world)) {
                unexpected.add(world);
                conflicts.add("protected_cell@" + world.toShortString());
                continue;
            }
            BlockState target = BlockPatchTargetResolver.resolve(patch);
            if (target == null) {
                conflicts.add("invalid_target@" + world.toShortString());
                continue;
            }
            if (op == BuildingEditOperation.REPLACE_ROOF_MATERIAL) {
                if (BlockPatch.REMOVE.equals(patch.action()) || target.isAir()) {
                    conflicts.add("geometry_change_forbidden@" + world.toShortString());
                    continue;
                }
            } else if (op == BuildingEditOperation.REPLACE_FBD_PROGRAM) {
                // Full rebuild may REMOVE vanished cells and PLACE new geometry.
            } else if (op == BuildingEditOperation.SEAL_WINDOW) {
                if (BlockPatch.REMOVE.equals(patch.action()) || target.isAir()) {
                    conflicts.add("seal_must_place_wall@" + world.toShortString());
                    continue;
                }
            } else if (op == BuildingEditOperation.INSERT_WINDOW) {
                // Opening may REPLACE wall→glass or REMOVE; never touch protected cells (checked above).
                if (BlockPatch.REMOVE.equals(patch.action()) && !target.isAir()) {
                    // REMOVE payloads sometimes still carry a placeholder block id — treat as open.
                }
            }
            String expected = scope.expectedWorldStates().get(world);
            if (expected == null || expected.isBlank()) {
                conflicts.add("expected_state_missing@" + world.toShortString());
                continue;
            }
            String live = liveWorldStates.get(world);
            if (live == null || live.isBlank()) {
                conflicts.add("live_state_missing@" + world.toShortString());
                continue;
            }
            if (!statesMatch(expected, live)) {
                conflicts.add("world_conflict@" + world.toShortString()
                        + " expected=" + expected + " live=" + live);
                continue;
            }
            anyChange = true;
        }
        if (!conflicts.isEmpty()) {
            return BuildingEditValidation.rejected(conflicts, unexpected);
        }
        if (!anyChange) {
            String code = op.isWindowOp() ? "no_effective_window_change"
                    : (op.isFbdReplace() ? "no_effective_fbd_change" : "no_effective_roof_change");
            return BuildingEditValidation.rejected(List.of(code), List.of());
        }
        String part = switch (op) {
            case REPLACE_ROOF_MATERIAL -> "ROOF";
            case INSERT_WINDOW -> "WINDOW_INSERT";
            case SEAL_WINDOW -> "WINDOW_SEAL";
            case REPLACE_FBD_PROGRAM -> "FBD_REPLACE";
        };
        return BuildingEditValidation.accepted(List.of(part));
    }

    private static boolean statesMatch(String expected, String live) {
        if (Objects.equals(expected, live)) return true;
        if (expected != null && live != null
                && expected.toLowerCase(Locale.ROOT).equals(live.toLowerCase(Locale.ROOT))) {
            return true;
        }
        BlockState a = BlockPatchTargetResolver.parse(expected);
        BlockState b = BlockPatchTargetResolver.parse(live);
        if (a == null || b == null) return false;
        return a.equals(b)
                || Objects.equals(BlockStateStringUtil.fromState(a), BlockStateStringUtil.fromState(b));
    }
}
