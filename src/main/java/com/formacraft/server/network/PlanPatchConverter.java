package com.formacraft.server.network;

import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.BlockPatchTargetResolver;
import net.minecraft.util.math.BlockPos;
import java.util.ArrayList;
import java.util.List;

/** Converts plan-local patches to world placements with execution's strict target semantics. */
public final class PlanPatchConverter {
    private PlanPatchConverter() {}
    public record Result(List<PlannedBlock> blocks, int invalid) {}
    public static Result convert(List<BlockPatch> patches, BlockPos origin) {
        List<PlannedBlock> blocks = new ArrayList<>();
        int invalid = 0;
        for (BlockPatch patch : patches) {
            var state = BlockPatchTargetResolver.resolve(patch);
            if (state == null) { invalid++; continue; }
            blocks.add(new PlannedBlock(origin.add(patch.dx(), patch.dy(), patch.dz()), state));
        }
        return new Result(blocks, invalid);
    }
}
