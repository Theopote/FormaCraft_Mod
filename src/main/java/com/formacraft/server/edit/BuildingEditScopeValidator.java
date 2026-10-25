package com.formacraft.server.edit;

import com.formacraft.common.component.transform.BlockStateStringUtil;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.BlockPatchTargetResolver;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Independent of the LLM: every effective write must land in allowedCells,
 * and protected / expected states must not be violated.
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

        if (scope.operation() != BuildingEditOperation.REPLACE_ROOF_MATERIAL) {
            conflicts.add("unsupported_operation:" + scope.operation());
            return BuildingEditValidation.rejected(conflicts, unexpected);
        }
        if (currentRevision != scope.baseRevision()) {
            conflicts.add("revision_mismatch expected=" + scope.baseRevision() + " actual=" + currentRevision);
            return BuildingEditValidation.rejected(conflicts, unexpected);
        }
        if (scope.allowedCells().isEmpty()) {
            conflicts.add("roof_cells_unavailable");
            return BuildingEditValidation.rejected(conflicts, unexpected);
        }
        if (patches == null || patches.isEmpty()) {
            conflicts.add("empty_patch");
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
            if (BlockPatch.REMOVE.equals(patch.action()) || target.isAir()) {
                conflicts.add("geometry_change_forbidden@" + world.toShortString());
                continue;
            }
            String expected = scope.expectedWorldStates().get(world);
            if (expected != null && liveWorldStates != null) {
                String live = liveWorldStates.get(world);
                if (live != null && !statesMatch(expected, live)) {
                    conflicts.add("world_conflict@" + world.toShortString()
                            + " expected=" + expected + " live=" + live);
                    continue;
                }
            }
            anyChange = true;
        }
        if (!conflicts.isEmpty()) {
            return BuildingEditValidation.rejected(conflicts, unexpected);
        }
        if (!anyChange) {
            return BuildingEditValidation.rejected(List.of("no_effective_roof_change"), List.of());
        }
        return BuildingEditValidation.accepted(List.of("ROOF"));
    }

    private static boolean statesMatch(String expected, String live) {
        if (Objects.equals(expected, live)) return true;
        BlockState a = BlockPatchTargetResolver.parse(expected);
        BlockState b = BlockPatchTargetResolver.parse(live);
        if (a == null || b == null) return false;
        return a.equals(b)
                || Objects.equals(BlockStateStringUtil.fromState(a), BlockStateStringUtil.fromState(b));
    }
}
