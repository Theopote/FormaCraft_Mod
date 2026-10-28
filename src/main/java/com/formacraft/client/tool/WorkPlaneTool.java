package com.formacraft.client.tool;

import com.formacraft.client.interaction.CursorRaycastHelper;
import com.formacraft.client.ui.input.InputRouter;
import com.formacraft.client.ui.toast.HudToast;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/** Shared horizontal drawing plane with a draggable vertical height handle. */
public final class WorkPlaneTool implements FormacraftTool {
    public static final WorkPlaneTool INSTANCE = new WorkPlaneTool();
    private final WorkPlaneState state = new WorkPlaneState();
    private Object worldScope;
    private Vec3d dragStart;
    private int dragHeight;

    private WorkPlaneTool() {}
    public WorkPlaneState state() { ensureScope(); return state; }
    private void ensureScope() {
        var client = MinecraftClient.getInstance();
        Object world = client == null ? null : client.world;
        if (worldScope != world) { state.clear(); dragStart = null; worldScope = world; }
    }
    @Override public String getId() { return "work_plane"; }
    @Override public Text getDisplayName() { return Text.literal("基准平面"); }
    @Override public boolean hasPersistentOverlay() { return state().hasPlane(); }

    public void changeHeight(int delta) { setHeight(state().height() + delta); }
    public void setHeight(int y) {
        var client = MinecraftClient.getInstance();
        if (client == null || client.world == null || client.player == null) return;
        if (!state().hasPlane()) state.set(client.player.getBlockPos());
        state.setHeight(y, client.world.getBottomY(), client.world.getBottomY() + client.world.getHeight() - 1);
    }
    public void clear() { state().clear(); dragStart = null; }

    @Override public boolean onMouseClick(double mx, double my, int button) {
        if (button != 0 && button != 1) return false;
        var state = state();
        if (button == 1) { state.toggleLock(); return true; }
        Vec3d handle = dragPosition();
        if (handle != null && overHandle()) {
            dragStart = handle;
            dragHeight = state.height();
            return true;
        }
        if (state.locked()) { HudToast.show("基准平面已锁定；右键解锁后可重新取点"); return true; }
        var hit = CursorRaycastHelper.getLastBlockHit();
        if (hit != null) {
            state.set(hit.getBlockPos().up());
            setHeight(state.height());
        }
        return true;
    }

    private Vec3d dragPosition() {
        if (!state.hasPlane()) return null;
        var ray = CursorRaycastHelper.getRayDirection();
        var origin = CursorRaycastHelper.getRayOrigin();
        if (ray == null || origin == null) return null;
        return ToolGeometry.axisPoint(origin, ray, Vec3d.ofCenter(state.center()).add(0, 1.5, 0), Direction.Axis.Y);
    }
    private boolean overHandle() {
        Vec3d point = Vec3d.ofCenter(state.center()).add(0, 1.5, 0);
        Vec3d origin = CursorRaycastHelper.getRayOrigin(), ray = CursorRaycastHelper.getRayDirection();
        if (origin == null || ray == null) return false;
        double distance = point.subtract(origin).dotProduct(ray);
        return distance > 0 && origin.add(ray.multiply(distance)).squaredDistanceTo(point) < 0.3;
    }
    @Override public void tick() {
        if (dragStart == null) return;
        if (!InputRouter.leftDown) { dragStart = null; return; }
        Vec3d point = dragPosition();
        if (point != null) setHeight(dragHeight + (int) Math.round(point.y - dragStart.y));
    }
    @Override public void cancelDraft() {
        if (dragStart != null) { setHeight(dragHeight); dragStart = null; }
    }
    @Override public void onDeactivate() { dragStart = null; }

    @Override public void renderWorld(ToolWorldRenderContext ctx) {
        if (!state().hasPlane()) return;
        var p = state.center();
        double cx = p.getX() + 0.5, cy = p.getY() + 0.015, cz = p.getZ() + 0.5;
        for (int i = -16; i <= 16; i++) {
            int alpha = i % 4 == 0 ? 150 : 55;
            ToolRenderUtil.line(ctx, cx - 16, cy, cz + i, cx + 16, cy, cz + i, 60, 220, 255, alpha);
            ToolRenderUtil.line(ctx, cx + i, cy, cz - 16, cx + i, cy, cz + 16, 60, 220, 255, alpha);
        }
        if (ToolManager.isActive(getId())) {
            ToolRenderUtil.line(ctx, cx, cy, cz, cx, cy + 2.5, cz, 255, 235, 80, 255);
            ToolRenderUtil.line(ctx, cx, cy + 2.5, cz, cx - 0.3, cy + 2.1, cz, 255, 235, 80, 255);
            ToolRenderUtil.line(ctx, cx, cy + 2.5, cz, cx + 0.3, cy + 2.1, cz, 255, 235, 80, 255);
        }
        if (state.fixed()) {
            var hover = CursorRaycastHelper.getDrawingBlockPos();
            if (hover != null) {
                double x = hover.getX() + 0.5, z = hover.getZ() + 0.5;
                ToolRenderUtil.line(ctx, x - 0.25, cy + 0.02, z, x + 0.25, cy + 0.02, z, 255, 255, 255, 240);
                ToolRenderUtil.line(ctx, x, cy + 0.02, z - 0.25, x, cy + 0.02, z + 0.25, 255, 255, 255, 240);
            }
        }
    }
}
