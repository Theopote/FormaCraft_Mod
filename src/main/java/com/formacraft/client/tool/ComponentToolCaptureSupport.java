package com.formacraft.client.tool;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * 构件捕获：选区与 anchor 合法性校验。
 */
final class ComponentToolCaptureSupport {
    private ComponentToolCaptureSupport() {}

    static boolean isAnchorAllowed(ComponentToolState state, BlockPos pos, ComponentCaptureDraft draft) {
        if (pos == null) return false;
        if (state.useLibrary) return true;
        if (!SelectionTool.INSTANCE.hasSelection() && !draft.hasExplicitSelection()
                && draft.selection.aabbMin == null && draft.selection.aabbMax == null) return false;

        if (draft.hasExplicitSelection()) {
            if (draft.selection.blocks.contains(pos)) return true;
            if (!draft.anchor.allowOutsideSelection) return false;
            return isAnchorAdjacentToExplicitSelection(pos, draft);
        }

        if (isInsideSelection(pos, draft)) return true;
        if (!draft.anchor.allowOutsideSelection) return false;
        return isInsideExpandedSelection(pos, 1, draft);
    }

    static boolean isInSelectionBlocks(ComponentToolState state, ComponentCaptureDraft draft, BlockPos pos) {
        if (pos == null) return false;
        if (draft.hasExplicitSelection()) {
            return draft.selection.blocks.contains(pos);
        }
        return isInsideSelection(pos, draft);
    }

    static boolean isAnchorAdjacentToExplicitSelection(BlockPos pos, ComponentCaptureDraft draft) {
        if (pos == null || !draft.hasExplicitSelection()) return false;
        for (Direction d : Direction.values()) {
            if (draft.selection.blocks.contains(pos.offset(d))) {
                return true;
            }
        }
        return false;
    }

    static boolean isInsideExpandedSelection(BlockPos pos, int pad, ComponentCaptureDraft draft) {
        if (pos == null) return false;
        BlockPos min = SelectionTool.INSTANCE.getMin();
        BlockPos max = SelectionTool.INSTANCE.getMax();
        if (min == null || max == null) {
            min = draft.selection.aabbMin;
            max = draft.selection.aabbMax;
        }
        if (min == null || max == null) return false;
        return pos.getX() >= (min.getX() - pad) && pos.getX() <= (max.getX() + pad)
                && pos.getY() >= (min.getY() - pad) && pos.getY() <= (max.getY() + pad)
                && pos.getZ() >= (min.getZ() - pad) && pos.getZ() <= (max.getZ() + pad);
    }

    static boolean isInsideSelection(BlockPos pos, ComponentCaptureDraft draft) {
        if (pos == null) return false;
        BlockPos min = SelectionTool.INSTANCE.getMin();
        BlockPos max = SelectionTool.INSTANCE.getMax();
        if (min == null || max == null) {
            min = draft.selection.aabbMin;
            max = draft.selection.aabbMax;
        }
        if (min == null || max == null) return false;
        return pos.getX() >= min.getX() && pos.getX() <= max.getX()
                && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
    }

    static boolean hasValidSelection(ComponentCaptureDraft draft) {
        if (draft.hasExplicitSelection()) return true;
        if (SelectionTool.INSTANCE.hasSelection()) return true;
        return draft.selection.aabbMin != null && draft.selection.aabbMax != null;
    }
}
