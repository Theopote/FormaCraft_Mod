package com.formacraft.client.tool;

import com.formacraft.client.interaction.CursorRaycastHelper;
import com.formacraft.common.model.constraint.ProtectedZone;
import com.formacraft.client.network.FormaCraftClientNetworking;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 禁区/保护区工具：两点框选一个盒子，确认后加入列表（可多个）。
 *
 * - 左键：第一次设置起点；第二次设置终点并“提交”为一个 ProtectedZone
 * - 右键：取消当前正在选择（不清空已提交的列表）
 */
public final class ProtectedZoneTool implements FormacraftTool {
    public static final ProtectedZoneTool INSTANCE = new ProtectedZoneTool();

    private final java.util.function.Consumer<List<ProtectedZone>> sync;

    private ProtectedZoneTool() { this(FormaCraftClientNetworking::sendProtectedZoneSync); }

    ProtectedZoneTool(java.util.function.Consumer<List<ProtectedZone>> sync) { this.sync = sync; }

    private BlockPos start;
    private BlockPos end;
    private boolean selecting = false;

    private final List<ProtectedZone> zones = new ArrayList<>();

    @Override
    public String getId() {
        return "protected_zone";
    }

    @Override
    public Text getDisplayName() {
        return Text.literal("禁区/保护区");
    }

    @Override
    public boolean onMouseClick(double mx, double my, int button) {
        // 左键/右键都由 InputRouter 转发（面板外）
        if (button != 0 && button != 1) return false;

        if (button == 1) {
            // 右键：取消本次框选
            selecting = false;
            start = null;
            end = null;
            return true;
        }

        BlockHitResult hit = CursorRaycastHelper.getLastBlockHit();
        if (hit == null) return true;

        selectPoint(hit.getBlockPos());
        return true;
    }

    void selectPoint(BlockPos pos) {
        if (!selecting) {
            start = pos;
            end = pos;
            selecting = true;
        } else {
            end = pos;
            selecting = false;
            if (start != null && end != null) {
                zones.add(new ProtectedZone(start, end).normalized());
                syncToServer();
            }
            start = null;
            end = null;
        }
    }

    @Override
    public void tick() {
        if (!selecting) return;
        BlockHitResult hit = CursorRaycastHelper.getLastBlockHit();
        if (hit != null) end = hit.getBlockPos();
    }

    @Override
    public void renderWorld(ToolWorldRenderContext ctx) {
        // 已提交区域：红色盒子 + 顶部斜线
        for (ProtectedZone z : zones) {
            if (z == null || z.min() == null || z.max() == null) continue;
            renderZone(ctx, z, false);
        }

        // 正在框选：橙色盒子（预览）
        if (selecting && start != null && end != null) {
            renderZone(ctx, new ProtectedZone(start, end).normalized(), true);
        }
    }

    private void renderZone(ToolWorldRenderContext ctx, ProtectedZone z, boolean preview) {
        BlockPos min = z.min();
        BlockPos max = z.max();
        if (min == null || max == null) return;

        Box worldBox = new Box(
                min.getX(), min.getY(), min.getZ(),
                max.getX() + 1, max.getY() + 1, max.getZ() + 1
        ).expand(0.01);
        Box box = worldBox.offset(-ctx.cameraX, -ctx.cameraY, -ctx.cameraZ);

        float r = preview ? 1.00f : 1.00f;
        float g = preview ? 0.65f : 0.25f;
        float b = preview ? 0.10f : 0.10f;
        float a = preview ? 0.65f : 0.85f;
        VertexRendering.drawBox(ctx.matrices.peek(), ctx.vertexConsumer, box, r, g, b, a);

        // 顶部斜线：按斜率裁剪进盒子，避免长宽比大时端点独立 clamp 导致畸变
        int topY = max.getY() + 1;
        double minX = min.getX();
        double maxX = max.getX() + 1;
        double minZ = min.getZ();
        double maxZ = max.getZ() + 1;
        int span = (max.getX() - min.getX()) + (max.getZ() - min.getZ());
        int step = Math.max(2, span / 32);
        double y = topY + 0.02;
        // 斜线方向：从 (x, minZ) → (x - height, maxZ)
        for (int x = min.getX(); x <= max.getX() + (max.getZ() - min.getZ()); x += step) {
            double x1 = x;
            double z1 = minZ;
            double x2 = x - (max.getZ() - min.getZ());
            double z2 = maxZ;
            double[] clipped = ToolGeometry.clipSegmentXZ(x1, z1, x2, z2, minX, maxX, minZ, maxZ);
            if (clipped == null) continue;
            ToolRenderUtil.line(ctx,
                    clipped[0], y, clipped[1],
                    clipped[2], y, clipped[3],
                    255, preview ? 200 : 80, 80, 140);
        }
    }

    public List<ProtectedZone> getZones() {
        return Collections.unmodifiableList(zones);
    }

    public boolean hasZones() {
        return !zones.isEmpty();
    }

    public boolean canClear() { return hasZones() || selecting || start != null || end != null; }

    @Override
    public boolean hasPersistentOverlay() { return hasZones(); }

    @Override
    public void cancelDraft() {
        selecting = false;
        start = null;
        end = null;
    }

    @Override
    public void onDeactivate() { cancelDraft(); }

    public void clearZones() {
        zones.clear();
        cancelDraft();
        syncToServer();
    }

    @Override
    public void resetWorldState() {
        zones.clear();
        cancelDraft();
    }

    private void syncToServer() {
        sync.accept(List.copyOf(zones));
    }
}


