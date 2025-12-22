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

    private SymmetryTool() {}

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

    public void cycleMode() {
        mode = switch (mode) {
            case NONE -> SymmetryMode.MIRROR_X;
            case MIRROR_X -> SymmetryMode.MIRROR_Z;
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

    public BlockPos getAxisA() { return axisA; }
    public BlockPos getAxisB() { return axisB; }

    public void clearAxis() {
        axisA = null;
        axisB = null;
        selecting = false;
    }

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

        BlockPos pos = hit.getBlockPos();
        if (!selecting) {
            axisA = pos;
            axisB = pos;
            selecting = true;
        } else {
            axisB = pos;
            selecting = false;
        }
        return true;
    }

    @Override
    public void tick() {
        if (mode != SymmetryMode.CUSTOM_AXIS) return;
        if (!selecting) return;
        BlockHitResult hit = CursorRaycastHelper.getLastBlockHit();
        if (hit != null) axisB = hit.getBlockPos();
    }

    @Override
    public void renderWorld(ToolWorldRenderContext ctx) {
        if (mode == SymmetryMode.NONE) return;

        // 轴线颜色：青色
        int r = 80, g = 255, b = 220, a = 220;

        if (mode == SymmetryMode.CUSTOM_AXIS) {
            if (axisA == null || axisB == null) return;
            double y = Math.min(axisA.getY(), axisB.getY()) + 0.05;
            ToolRenderUtil.line(ctx,
                    axisA.getX() + 0.5, y, axisA.getZ() + 0.5,
                    axisB.getX() + 0.5, y, axisB.getZ() + 0.5,
                    r, g, b, a);
            return;
        }

        // 预设模式：用“选区中心”做可视化轴线（没有选区就不画）
        if (!SelectionTool.INSTANCE.hasSelection()) return;
        BlockPos min = SelectionTool.INSTANCE.getMin();
        BlockPos max = SelectionTool.INSTANCE.getMax();
        if (min == null || max == null) return;

        double cx = (min.getX() + max.getX() + 1) / 2.0;
        double cz = (min.getZ() + max.getZ() + 1) / 2.0;
        double y = min.getY() + 0.05;

        // MIRROR_X：镜像平面 x = cx → 可视化为 Z 方向的轴线
        if (mode == SymmetryMode.MIRROR_X || mode == SymmetryMode.BOTH) {
            ToolRenderUtil.line(ctx, cx, y, min.getZ(), cx, y, max.getZ() + 1, r, g, b, a);
        }
        // MIRROR_Z：镜像平面 z = cz → 可视化为 X 方向的轴线
        if (mode == SymmetryMode.MIRROR_Z || mode == SymmetryMode.BOTH) {
            ToolRenderUtil.line(ctx, min.getX(), y, cz, max.getX() + 1, y, cz, r, g, b, a);
        }
    }
}


