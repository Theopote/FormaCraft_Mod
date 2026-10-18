package com.formacraft.server.preview;

import com.formacraft.common.component.transform.BlockStateStringUtil;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.world.BlockMutationAccess;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Capture / verify preview-time world cells for Apply conflict detection.
 * Reuses {@link BlockMutationAccess} — no parallel transaction system.
 */
public final class PreviewWorldStateCapture {
    private PreviewWorldStateCapture() {}

    public static Map<BlockPos, String> capture(
            ServerWorld world, BlockPos origin, List<BlockPatch> patches) {
        if (world == null || origin == null || patches == null) {
            return Map.of();
        }
        return capture(BlockMutationAccess.forWorld(world), origin, patches);
    }

    public static Map<BlockPos, String> capture(
            BlockMutationAccess access, BlockPos origin, List<BlockPatch> patches) {
        LinkedHashMap<BlockPos, String> out = new LinkedHashMap<>();
        if (access == null || origin == null || patches == null) {
            return out;
        }
        for (BlockPatch p : patches) {
            if (p == null) continue;
            BlockPos pos = origin.add(p.dx(), p.dy(), p.dz()).toImmutable();
            if (!access.isInsideHeight(pos) || !access.isChunkReady(pos)) {
                continue;
            }
            BlockState state = access.getState(pos);
            out.put(pos, BlockStateStringUtil.fromState(state));
        }
        return out;
    }

    /**
     * @return null if OK; otherwise a short conflict detail.
     */
    public static String conflictDetail(
            BlockMutationAccess access, Map<BlockPos, String> expected) {
        if (expected == null || expected.isEmpty()) {
            return null;
        }
        if (access == null) {
            return "access_null";
        }
        int conflicts = 0;
        BlockPos sample = null;
        for (var e : expected.entrySet()) {
            BlockPos pos = e.getKey();
            if (!access.isInsideHeight(pos) || !access.isChunkReady(pos)) {
                conflicts++;
                if (sample == null) sample = pos;
                continue;
            }
            String live = BlockStateStringUtil.fromState(access.getState(pos));
            String exp = e.getValue() == null ? "" : e.getValue();
            if (!exp.equalsIgnoreCase(live == null ? "" : live)) {
                conflicts++;
                if (sample == null) sample = pos;
            }
        }
        if (conflicts == 0) return null;
        return "conflicts=" + conflicts + (sample == null ? "" : "_at=" + sample);
    }
}
