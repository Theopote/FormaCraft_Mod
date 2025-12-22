package com.formacraft.client.preview;

import com.formacraft.client.preview.outline.OutlineQuad;
import com.formacraft.client.preview.outline.PatchOutlineBuilder;
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
    private static final List<BlockPatch> rejectedPatches = new ArrayList<>();
    private static List<OutlineQuad> placeOutline = List.of();
    private static List<OutlineQuad> replaceOutline = List.of();
    private static List<OutlineQuad> removeOutline = List.of();
    private static List<OutlineQuad> rejectedOutline = List.of();

    public static void setPreview(BlockPos originPos, List<BlockPatch> newPatches) {
        setPreview(originPos, newPatches, List.of());
    }

    public static void setPreview(BlockPos originPos, List<BlockPatch> accepted, List<BlockPatch> rejected) {
        origin = originPos != null ? originPos : BlockPos.ORIGIN;
        patches.clear();
        rejectedPatches.clear();
        if (accepted != null) patches.addAll(accepted);
        if (rejected != null) rejectedPatches.addAll(rejected);
        enabled = !patches.isEmpty() || !rejectedPatches.isEmpty();

        PatchOutlineBuilder.Result r = PatchOutlineBuilder.build(origin, patches);
        placeOutline = r.placeOutline() != null ? r.placeOutline() : List.of();
        replaceOutline = r.replaceOutline() != null ? r.replaceOutline() : List.of();
        removeOutline = r.removeOutline() != null ? r.removeOutline() : List.of();

        PatchOutlineBuilder.Result rr = PatchOutlineBuilder.build(origin, rejectedPatches);
        // rejected 统一用一套 outline 渲染即可（不区分 place/replace/remove）
        List<OutlineQuad> out = new ArrayList<>();
        if (rr.placeOutline() != null) out.addAll(rr.placeOutline());
        if (rr.replaceOutline() != null) out.addAll(rr.replaceOutline());
        if (rr.removeOutline() != null) out.addAll(rr.removeOutline());
        rejectedOutline = out;
    }

    public static void clear() {
        enabled = false;
        patches.clear();
        rejectedPatches.clear();
        origin = BlockPos.ORIGIN;
        placeOutline = List.of();
        replaceOutline = List.of();
        removeOutline = List.of();
        rejectedOutline = List.of();
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

    public static List<BlockPatch> getRejectedPatches() {
        return rejectedPatches;
    }

    public static List<OutlineQuad> getPlaceOutline() {
        return placeOutline;
    }

    public static List<OutlineQuad> getReplaceOutline() {
        return replaceOutline;
    }

    public static List<OutlineQuad> getRemoveOutline() {
        return removeOutline;
    }

    public static List<OutlineQuad> getRejectedOutline() {
        return rejectedOutline;
    }
}

