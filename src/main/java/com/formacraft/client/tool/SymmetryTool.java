package com.formacraft.client.tool;

import com.formacraft.client.interaction.CursorRaycastHelper;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;

/**
 * 对称/镜像约束工具：
 * - MIRROR_X / MIRROR_Z / BOTH：用于 Prompt 约束（默认以“选区中心”作为对称基准）
 * - CUSTOM_AXIS：两点定义一条对称轴线（XZ 平面）
 *
 * 交互（面板外）：
 * - CUSTOM_AXIS：左键 A → 左键 B 完成；右键取消
 */
public final class SymmetryTool implements FormacraftTool {
    public static final SymmetryTool INSTANCE = new SymmetryTool();

    SymmetryTool() {}

    private SymmetryMode mode = SymmetryMode.NONE;

    private BlockPos axisA;
    private BlockPos axisB;
    private boolean selecting = false;

    @Override
    public String getId() {
        return "symmetry";
    }

    @Override
    public Text getDisplayName() {
        return Text.literal("对称/镜像");
    }

    public SymmetryMode getMode() {
        return mode;
    }

    @Override
    public boolean hasPersistentOverlay() { return mode != SymmetryMode.NONE; }

    public void cycleMode() {
        mode = switch (mode) {
            case NONE -> SymmetryMode.MIRROR_X;
            case MIRROR_X -> SymmetryMode.MIRROR_Y;
            case MIRROR_Y -> SymmetryMode.MIRROR_Z;
            case MIRROR_Z -> SymmetryMode.BOTH;
            case BOTH -> SymmetryMode.CUSTOM_AXIS;
            case CUSTOM_AXIS -> SymmetryMode.NONE;
        };
        if (mode != SymmetryMode.CUSTOM_AXIS) {
            selecting = false;
            axisA = null;
            axisB = null;
        }
    }

    public boolean hasAxis() {
        return axisA != null && axisB != null && !selecting;
    }

    public boolean canClearAxis() { return axisA != null || axisB != null || selecting; }

    public BlockPos getAxisA() { return axisA; }
    public BlockPos getAxisB() { return axisB; }

    public void clearAxis() {
        axisA = null;
        axisB = null;
        selecting = false;
    }

    @Override
    public void cancelDraft() {
        if (selecting) clearAxis();
    }

    @Override
    public void onDeactivate() { cancelDraft(); }

    @Override
    public boolean onMouseClick(double mx, double my, int button) {
        if (button != 0 && button != 1) return false;
        if (mode != SymmetryMode.CUSTOM_AXIS) return false;

        if (button == 1) {
            clearAxis();
            return true;
        }

        BlockHitResult hit = CursorRaycastHelper.getLastBlockHit();
        if (hit == null) return true;

        selectAxisPoint(hit.getBlockPos());
        return true;
    }

    void selectAxisPoint(BlockPos pos) {
        if (!selecting) {
            axisA = pos;
            axisB = pos;
            selecting = true;
        } else {
            if (ToolGeometry.sameXZ(axisA, pos)) {
                com.formacraft.client.ui.toast.HudToast.show("对称轴需要两个不同的水平位置", true);
                return;
            }
            axisB = pos;
            selecting = false;
        }
    }

    @Override
    public void tick() {
        if (mode != SymmetryMode.CUSTOM_AXIS) return;
        if (!selecting) return;
        BlockHitResult hit = CursorRaycastHelper.getLastBlockHit();
        if (hit != null) axisB = hit.getBlockPos();
    }

    public String getModeLabel() {
        return switch (mode) {
            case NONE -> "关闭";
            case MIRROR_X -> "X 镜像（红色 YZ 面）";
            case MIRROR_Y -> "Y 镜像（绿色 XZ 面）";
            case MIRROR_Z -> "Z 镜像（蓝色 XY 面）";
            case BOTH -> "X/Z 双面镜像";
            case CUSTOM_AXIS -> "自定义轴（紫色，按主方向对齐）";
        };
    }

    @Override
    public void renderWorld(ToolWorldRenderContext ctx) {
        if (mode == SymmetryMode.NONE || ctx == null) return;
        var client = net.minecraft.client.MinecraftClient.getInstance();
        if (client == null || client.player == null) return;
        var bc = com.formacraft.client.buildcontext.BuildContextResolver.resolve();
        BlockPos origin = bc != null && bc.origin != null ? bc.origin : client.player.getBlockPos();
        var selection = SelectionTool.INSTANCE.hasSelection()
                ? new com.formacraft.common.buildcontext.SelectionBox(SelectionTool.INSTANCE.getMin(), SelectionTool.INSTANCE.getMax()) : null;
        var planes = SymmetryOverlayGeometry.planes(mode, selection, origin, axisA, axisB);
        for (var plane : planes) {
            for (int i = 0; i <= 8; i++) {
                double t = i / 8.0;
                boolean border = i == 0 || i == 8;
                var a = plane.corner().add(plane.u().multiply(t));
                var b = a.add(plane.v());
                drawPlaneLine(ctx, plane, a, b, border ? 230 : 85);
                a = plane.corner().add(plane.v().multiply(t));
                b = a.add(plane.u());
                drawPlaneLine(ctx, plane, a, b, border ? 230 : 85);
            }
        }
        if (mode == SymmetryMode.CUSTOM_AXIS && axisA != null && axisB != null) {
            ToolRenderUtil.line(ctx, axisA.getX() + 0.5, axisA.getY() + 0.05, axisA.getZ() + 0.5,
                    axisB.getX() + 0.5, axisB.getY() + 0.05, axisB.getZ() + 0.5, 240, 180, 255, 255);
        }
    }

    private static void drawPlaneLine(ToolWorldRenderContext ctx, SymmetryOverlayGeometry.Plane plane,
                                      net.minecraft.util.math.Vec3d a, net.minecraft.util.math.Vec3d b, int alpha) {
        ToolRenderUtil.line(ctx, a.x, a.y, a.z, b.x, b.y, b.z, plane.red(), plane.green(), plane.blue(), alpha);
    }
}
