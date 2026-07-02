package com.formacraft.client.buildcontext;

import com.formacraft.client.interaction.AnchorState;
import com.formacraft.client.interaction.CursorRaycastHelper;
import com.formacraft.client.tool.OutlineMode;
import com.formacraft.client.tool.OutlineTool;
import com.formacraft.client.tool.ProtectedZoneTool;
import com.formacraft.client.tool.SelectionTool;
import com.formacraft.common.buildcontext.BuildContext;
import com.formacraft.common.buildcontext.OutlineShape;
import com.formacraft.common.buildcontext.SelectionBox;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.hit.BlockHitResult;

import java.util.List;

/**
 * BuildContextResolver：把“当前工具/锚点/光标状态”统一解析为 BuildContext。
 *
 * 优先级（强→弱）：
 * Outline > Selection > Anchor > CursorHit(Implicit)
 */
public final class BuildContextResolver {
    private BuildContextResolver() {}

    private static final MinecraftClient client = MinecraftClient.getInstance();

    public static BuildContext resolve() {
        return resolve(false);
    }

    /** 当前 OutlineTool 形状（用于同步到服务端 Patch 过滤）。 */
    public static OutlineShape currentOutlineShape() {
        if (!OutlineTool.INSTANCE.hasShape()) return null;
        return toOutlineShape(OutlineTool.INSTANCE.getShape());
    }

    /**
     * @param restrictToSelection 当为 true（MODIFY_REGION）时，如果存在选区则强制以选区为主约束
     */
    public static BuildContext resolve(boolean restrictToSelection) {
        if (client == null || client.player == null || client.world == null) return null;
        Direction facing = client.player.getHorizontalFacing();
        List<com.formacraft.common.model.constraint.ProtectedZone> protectedZones = ProtectedZoneTool.INSTANCE.getZones();

        // 1) Outline（最强）
        if (!restrictToSelection && OutlineTool.INSTANCE.hasShape()) {
            OutlineTool.OutlineShape s = OutlineTool.INSTANCE.getShape();
            OutlineShape outline = toOutlineShape(s);
            BlockPos origin = outline != null ? outline.computeCenterOrigin() : null;
            if (origin == null) origin = client.player.getBlockPos();
            return BuildContext.forOutline(outline, origin, facing, protectedZones, restrictToSelection);
        }

        // 2) Selection
        if (SelectionTool.INSTANCE.hasSelection()) {
            BlockPos min = SelectionTool.INSTANCE.getMin();
            BlockPos max = SelectionTool.INSTANCE.getMax();
            SelectionBox box = new SelectionBox(min, max).normalized();
            BlockPos origin = box.bottomCenter();
            if (origin == null) origin = min != null ? min : client.player.getBlockPos();
            return BuildContext.forSelection(box, origin, facing, protectedZones, restrictToSelection);
        }

        // 2b) 如果 restrictToSelection=true 但没有选区，则继续按正常优先级（Anchor/Implicit）

        // 3) Anchor（显式）
        if (AnchorState.hasAnchor()) {
            Direction f = AnchorState.getFacing() != null ? AnchorState.getFacing() : facing;
            return BuildContext.forAnchor(AnchorState.get(), f, protectedZones, restrictToSelection);
        }

        // 4) Implicit（光标命中）
        BlockHitResult hit = CursorRaycastHelper.getLastBlockHit();
        if (hit != null) {
            BlockPos origin = hit.getBlockPos().up();
            return BuildContext.forImplicit(origin, facing, protectedZones, restrictToSelection);
        }

        // 5) 兜底
        return BuildContext.forImplicit(client.player.getBlockPos(), facing, protectedZones, restrictToSelection);
    }

    private static OutlineShape toOutlineShape(OutlineTool.OutlineShape s) {
        if (s == null) return null;
        if (s.mode() == OutlineMode.CIRCLE) {
            return new OutlineShape("circle", List.of(), s.center(), s.radius(), s.minY(), s.maxY());
        }
        return new OutlineShape("polygon", s.points(), null, 0, s.minY(), s.maxY());
    }
}


