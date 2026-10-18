package com.formacraft.server.build;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.patch.ServerPatchFilter;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MutationRegionPolicyTest {
    @Test void mirroredBlocksCannotEnterProtectedZone() {
        var forbidden = new com.formacraft.common.model.constraint.ProtectedZone(
                new BlockPos(-2, 0, -1), new BlockPos(-1, 4, 1));
        var snapshot = new com.formacraft.common.tool.ToolConstraintSnapshot(null, null, List.of(), List.of(forbidden),
                new com.formacraft.common.tool.ToolConstraintSnapshot.SymmetryConstraint(
                        com.formacraft.common.tool.MirrorSymmetryMode.MIRROR_X, null, null));
        var patch = new BlockPatch(BlockPatch.REMOVE, 1, 2, 0, null);
        var result = com.formacraft.common.patch.filter.PatchFilterPipeline.createDefault().apply(
                List.of(patch), BlockPos.ORIGIN, new com.formacraft.common.patch.filter.PatchFilterContext(snapshot));
        assertEquals(List.of(patch), result);
    }

    @Test void malformedOutlineIsRejectedByBothPaths() {
        var outline = new com.formacraft.common.buildcontext.OutlineShape("polygon", List.of(), null, 0, 0, 4);
        var build = new BuildConstraints(null, null, outline, List.of());
        var patch = new BlockPatch(BlockPatch.REMOVE, 0, 2, 0, null);
        assertFalse(build.allow(new BlockPos(0, 2, 0)));
        assertTrue(ServerPatchFilter.filter(BlockPos.ORIGIN, List.of(patch), List.of(), outline, false, null, null).accepted.isEmpty());
    }

    @Test void patchAndBuildAgreeOnSelectionBoundary() {
        BlockPos min = new BlockPos(-2, 0, -2), max = new BlockPos(2, 4, 2);
        BuildConstraints build = new BuildConstraints(min, max, null, List.of());
        for (int x = -3; x <= 3; x++) {
            BlockPos pos = new BlockPos(x, 2, 0);
            BlockPatch patch = new BlockPatch(BlockPatch.REMOVE, x, 2, 0, null);
            var result = ServerPatchFilter.filter(BlockPos.ORIGIN, List.of(patch), List.of(), null, true, min, max);
            assertEquals(build.allow(pos), !result.accepted.isEmpty(), pos.toString());
        }
    }
}
