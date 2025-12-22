package com.formacraft.common.buildcontext;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * AI 建造时的“唯一空间语义来源”：
 * Prompt / PatchFilter / Preview 等都应尽量从这里读取空间上下文。
 */
public final class BuildContext {

    public enum Mode {
        OUTLINE,
        SELECTION,
        ANCHOR,
        IMPLICIT_ANCHOR
    }

    public final Mode mode;
    public final BlockPos origin;
    public final Direction facing;
    public final SelectionBox selection; // nullable
    public final OutlineShape outline;   // nullable

    private BuildContext(Mode mode, BlockPos origin, Direction facing, SelectionBox selection, OutlineShape outline) {
        this.mode = mode;
        this.origin = origin;
        this.facing = facing;
        this.selection = selection;
        this.outline = outline;
    }

    public static BuildContext forOutline(OutlineShape outline, BlockPos origin, Direction facing) {
        return new BuildContext(Mode.OUTLINE, origin, facing, null, outline);
    }

    public static BuildContext forSelection(SelectionBox selection, BlockPos origin, Direction facing) {
        return new BuildContext(Mode.SELECTION, origin, facing, selection, null);
    }

    public static BuildContext forAnchor(BlockPos origin, Direction facing) {
        return new BuildContext(Mode.ANCHOR, origin, facing, null, null);
    }

    public static BuildContext forImplicit(BlockPos origin, Direction facing) {
        return new BuildContext(Mode.IMPLICIT_ANCHOR, origin, facing, null, null);
    }
}


