package com.formacraft.common.buildcontext;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.List;

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
    /** 始终叠加的安全约束：禁区/保护区（可为空/空列表） */
    public final List<com.formacraft.common.model.constraint.ProtectedZone> protectedZones;
    /** 是否强制“只允许修改选区内”（对应 MODIFY_REGION） */
    public final boolean restrictToSelection;

    private BuildContext(Mode mode, BlockPos origin, Direction facing, SelectionBox selection, OutlineShape outline,
                         List<com.formacraft.common.model.constraint.ProtectedZone> protectedZones,
                         boolean restrictToSelection) {
        this.mode = mode;
        this.origin = origin;
        this.facing = facing;
        this.selection = selection;
        this.outline = outline;
        this.protectedZones = protectedZones != null ? protectedZones : List.of();
        this.restrictToSelection = restrictToSelection;
    }

    public static BuildContext forOutline(OutlineShape outline, BlockPos origin, Direction facing,
                                          List<com.formacraft.common.model.constraint.ProtectedZone> protectedZones,
                                          boolean restrictToSelection) {
        return new BuildContext(Mode.OUTLINE, origin, facing, null, outline, protectedZones, restrictToSelection);
    }

    public static BuildContext forSelection(SelectionBox selection, BlockPos origin, Direction facing,
                                            List<com.formacraft.common.model.constraint.ProtectedZone> protectedZones,
                                            boolean restrictToSelection) {
        return new BuildContext(Mode.SELECTION, origin, facing, selection, null, protectedZones, restrictToSelection);
    }

    public static BuildContext forAnchor(BlockPos origin, Direction facing,
                                         List<com.formacraft.common.model.constraint.ProtectedZone> protectedZones,
                                         boolean restrictToSelection) {
        return new BuildContext(Mode.ANCHOR, origin, facing, null, null, protectedZones, restrictToSelection);
    }

    public static BuildContext forImplicit(BlockPos origin, Direction facing,
                                           List<com.formacraft.common.model.constraint.ProtectedZone> protectedZones,
                                           boolean restrictToSelection) {
        return new BuildContext(Mode.IMPLICIT_ANCHOR, origin, facing, null, null, protectedZones, restrictToSelection);
    }

    /** 用于 PatchPreview：保持空间约束不变，仅替换 origin（保证 dx/dy/dz 解析一致）。 */
    public BuildContext withOrigin(BlockPos newOrigin) {
        BlockPos o = newOrigin != null ? newOrigin : this.origin;
        return new BuildContext(this.mode, o, this.facing, this.selection, this.outline, this.protectedZones, this.restrictToSelection);
    }
}


