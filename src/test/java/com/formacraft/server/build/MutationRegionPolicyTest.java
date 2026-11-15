package com.formacraft.server.build;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.patch.ServerPatchFilter;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MutationRegionPolicyTest {
    @Test void mirroredBlocksCannotEnterProtectedZone() {
        // 对称扩展已迁移到规划期（FormaRequest.symmetryMode → 后端提示词），
        // 写入期不再做镜像扩展：补丁不会被悄悄翻倍，保护区也不需要"事后剔除镜像方块"。
        var forbidden = new com.formacraft.common.model.constraint.ProtectedZone(
                new BlockPos(-2, 0, -1), new BlockPos(-1, 4, 1));
        var patch = new BlockPatch(BlockPatch.REMOVE, 1, 2, 0, null);
        // (1,2,0) 在禁区之外：原样放行，且不会生成任何镜像方块。
        var result = ServerPatchFilter.filter(
                BlockPos.ORIGIN, List.of(patch), List.of(forbidden), null, false, null, null);
        assertEquals(List.of(patch), result.accepted);
        assertTrue(result.rejected.isEmpty());
        // (-1,2,0) 在禁区之内：拒绝。
        var inside = new BlockPatch(BlockPatch.REMOVE, -1, 2, 0, null);
        var rejected = ServerPatchFilter.filter(
                BlockPos.ORIGIN, List.of(inside), List.of(forbidden), null, false, null, null);
        assertTrue(rejected.accepted.isEmpty());
        assertEquals(List.of(inside), rejected.rejected);
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
