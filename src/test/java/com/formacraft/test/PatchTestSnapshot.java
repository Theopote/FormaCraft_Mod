package com.formacraft.test;

import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Replay compiled patches onto empty space, preserving overwrite/remove order.
 * Does not emulate world permissions, collision checks, terrain, or executor skips.
 */
public final class PatchTestSnapshot {
    private PatchTestSnapshot() {}

    public static Map<Vec3i, String> blocks(List<BlockPatch> patches) {
        Map<Vec3i, String> blocks = new HashMap<>();
        for (BlockPatch patch : patches) {
            Vec3i pos = new Vec3i(patch.dx(), patch.dy(), patch.dz());
            if (BlockPatch.REMOVE.equals(patch.action()) || "minecraft:air".equals(patch.targetBlock())) {
                blocks.remove(pos);
            } else {
                blocks.put(pos, patch.targetBlock());
            }
        }
        return blocks;
    }
}
