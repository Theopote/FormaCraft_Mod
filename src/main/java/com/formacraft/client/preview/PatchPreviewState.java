package com.formacraft.client.preview;

import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Patch 预览状态（仅客户端渲染，不改世界）。
 */
public final class PatchPreviewState {
    private PatchPreviewState() {}

    private static boolean enabled = false;
    private static BlockPos origin = BlockPos.ORIGIN;
    private static final List<BlockPatch> patches = new ArrayList<>();

    public static void setPreview(BlockPos originPos, List<BlockPatch> newPatches) {
        origin = originPos != null ? originPos : BlockPos.ORIGIN;
        patches.clear();
        if (newPatches != null) patches.addAll(newPatches);
        enabled = !patches.isEmpty();
    }

    public static void clear() {
        enabled = false;
        patches.clear();
        origin = BlockPos.ORIGIN;
    }

    public static boolean isEnabled() {
        return enabled && !patches.isEmpty();
    }

    public static BlockPos getOrigin() {
        return origin;
    }

    public static List<BlockPatch> getPatches() {
        return patches;
    }
}

