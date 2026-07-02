package com.formacraft.client.tool;

import com.formacraft.client.interaction.CursorRaycastHelper;
import com.formacraft.common.model.constraint.ProtectedZone;
import com.formacraft.common.network.FormaCraftNetworking;
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

    private ProtectedZoneTool() {}

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

        BlockPos pos = hit.getBlockPos();
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
        return true;
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

        // 顶部斜线（线条本身用 ToolRenderUtil）
        int topY = max.getY() + 1;
        int step = 2;
        // 斜线方向：从 (minX, minZ) -> (maxX, maxZ)
        for (int x = min.getX(); x <= max.getX() + (max.getZ() - min.getZ()); x += step) {
            int x1 = x;
            int z1 = min.getZ();
            int x2 = x - (max.getZ() - min.getZ());
            int z2 = max.getZ() + 1;

            // 裁剪到盒子范围
            // 简单做法：逐端点 clamp（视觉足够）
            x1 = clamp(x1, min.getX(), max.getX() + 1);
            x2 = clamp(x2, min.getX(), max.getX() + 1);

            double y = topY + 0.02;
            ToolRenderUtil.line(ctx,
                    x1, y, z1,
                    x2, y, z2,
                    255, preview ? 200 : 80, 80, 140);
        }
    }

    private static int clamp(int v, int a, int b) {
        if (v < a) return a;
        return Math.min(v, b);
    }

    public List<ProtectedZone> getZones() {
        return Collections.unmodifiableList(zones);
    }

    public boolean hasZones() {
        return !zones.isEmpty();
    }

    public void clearZones() {
        zones.clear();
        selecting = false;
        start = null;
        end = null;
        syncToServer();
    }

    private void syncToServer() {
        FormaCraftNetworking.sendProtectedZoneSync(zones);
    }
}


